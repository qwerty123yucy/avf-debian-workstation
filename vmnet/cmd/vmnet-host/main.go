package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	nicID       tcpip.NICID = 1
	mtu                     = 1500
	dnsVirtIP1              = "10.0.2.3"
	dnsVirtIP2              = "10.0.2.4"
	dnsVirtIPv6             = "fd00:a7f::3"
	gwVirtIP                = "10.0.2.1"
	gwVirtIPv6              = "fd00:a7f::1"
	maxBatch                = 64
	maxUDPFlows             = 512
)

var (
	dnsFilePath  string
	dnsMu        sync.Mutex
	dnsLastCheck time.Time
	dnsLastMod   time.Time
	dnsServers   = []string{"1.1.1.1", "8.8.8.8"}
	udpSem       = make(chan struct{}, maxUDPFlows)
)

// pickDNS lazily stats dnsFilePath at most once every 2s during active DNS lookups,
// avoiding any background polling timer when the VM is idle.
func pickDNS(idx int) string {
	dnsMu.Lock()
	defer dnsMu.Unlock()

	if dnsFilePath != "" {
		now := time.Now()
		if now.Sub(dnsLastCheck) >= 2*time.Second {
			dnsLastCheck = now
			if st, err := os.Stat(dnsFilePath); err == nil && !st.ModTime().Equal(dnsLastMod) {
				if raw, err := os.ReadFile(dnsFilePath); err == nil {
					var list []string
					for _, line := range strings.Split(string(raw), "\n") {
						s := strings.TrimSpace(line)
						if s == "" || strings.HasPrefix(s, "#") {
							continue
						}
						if ip := net.ParseIP(s); ip != nil && !ip.IsLoopback() {
							list = append(list, ip.String())
						}
					}
					if len(list) > 0 {
						dnsServers = list
						dnsLastMod = st.ModTime()
					}
				}
			}
		}
	}

	if len(dnsServers) > 0 {
		return dnsServers[idx%len(dnsServers)]
	}
	return "1.1.1.1"
}

func resolveDst(ipStr string, port uint16) string {
	if port == 53 {
		if ipStr == dnsVirtIP1 || ipStr == dnsVirtIPv6 {
			return net.JoinHostPort(pickDNS(0), "53")
		}
		if ipStr == dnsVirtIP2 {
			return net.JoinHostPort(pickDNS(1), "53")
		}
	}
	return net.JoinHostPort(ipStr, fmt.Sprintf("%d", port))
}

func createNetstack() (*stack.Stack, *channel.Endpoint, error) {
	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
	})

	ep := channel.New(2048, mtu, "")
	if err := s.CreateNIC(nicID, ep); err != nil {
		return nil, nil, fmt.Errorf("CreateNIC: %v", err)
	}
	if err := s.AddProtocolAddress(nicID, tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   tcpip.AddrFromSlice(net.ParseIP(gwVirtIP).To4()),
			PrefixLen: 24,
		},
	}, stack.AddressProperties{}); err != nil {
		return nil, nil, fmt.Errorf("AddProtocolAddress v4: %v", err)
	}
	if err := s.AddProtocolAddress(nicID, tcpip.ProtocolAddress{
		Protocol: ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   tcpip.AddrFromSlice(net.ParseIP(gwVirtIPv6).To16()),
			PrefixLen: 64,
		},
	}, stack.AddressProperties{}); err != nil {
		return nil, nil, fmt.Errorf("AddProtocolAddress v6: %v", err)
	}

	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	if err := s.SetPromiscuousMode(nicID, true); err != nil {
		return nil, nil, fmt.Errorf("SetPromiscuousMode: %v", err)
	}
	if err := s.SetSpoofing(nicID, true); err != nil {
		return nil, nil, fmt.Errorf("SetSpoofing: %v", err)
	}

	sack := tcpip.TCPSACKEnabled(true)
	_ = s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)

	// Dual-stack TCP forwarder -> host Android sockets (inherits com.avf.linux UID -> VpnService + kill-switch)
	tcpFwd := tcp.NewForwarder(s, 2<<20, 1024, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		dstIP := id.LocalAddress.String()
		dst := resolveDst(dstIP, id.LocalPort)

		dialer := net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}
		hostConn, err := dialer.Dial("tcp", dst)
		if err != nil {
			r.Complete(true) // Immediately send RST (e.g., ENETUNREACH on IPv6 when host is v4-only)
			return
		}

		var wq waiter.Queue
		ep, err2 := r.CreateEndpoint(&wq)
		if err2 != nil {
			_ = hostConn.Close()
			r.Complete(true)
			return
		}
		r.Complete(false)
		ep.SocketOptions().SetKeepAlive(true)

		guestConn := gonet.NewTCPConn(&wq, ep)
		go relayTCP(guestConn, hostConn)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	// Dual-stack UDP forwarder -> host Android UDP sockets
	udpFwd := udp.NewForwarder(s, func(r *udp.ForwarderRequest) bool {
		select {
		case udpSem <- struct{}{}:
		default:
			return false
		}
		id := r.ID()
		dstIP := id.LocalAddress.String()
		dst := resolveDst(dstIP, id.LocalPort)

		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil {
			<-udpSem
			return false
		}
		guestConn := gonet.NewUDPConn(&wq, ep)
		go func() {
			defer func() { <-udpSem }()
			relayUDP(guestConn, dst, id.LocalPort == 53)
		}()
		return true
	})
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	return s, ep, nil
}

func relayTCP(guest *gonet.TCPConn, host net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(host, guest)
		if tc, ok := host.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(guest, host)
		_ = guest.CloseWrite()
	}()
	wg.Wait()
	_ = guest.Close()
	_ = host.Close()
}

func relayUDP(guest *gonet.UDPConn, dst string, isDNS bool) {
	defer guest.Close()
	hostConn, err := net.DialTimeout("udp", dst, 5*time.Second)
	if err != nil {
		return
	}
	defer hostConn.Close()

	idleTimeout := 45 * time.Second
	if isDNS {
		idleTimeout = 5 * time.Second
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 65535)
		for {
			_ = hostConn.SetReadDeadline(time.Now().Add(idleTimeout))
			n, err := hostConn.Read(buf)
			if err != nil {
				return
			}
			if _, err := guest.Write(buf[:n]); err != nil {
				return
			}
		}
	}()

	buf := make([]byte, 65535)
	for {
		_ = guest.SetReadDeadline(time.Now().Add(idleTimeout))
		n, err := guest.Read(buf)
		if err != nil {
			break
		}
		if _, err := hostConn.Write(buf[:n]); err != nil {
			break
		}
	}
	_ = hostConn.SetReadDeadline(time.Now())
	<-done
}

func receiveVsockFile(uc *net.UnixConn) (*os.File, error) {
	var buf [1]byte
	oob := make([]byte, syscall.CmsgSpace(4))
	_ = uc.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, oobn, _, _, err := uc.ReadMsgUnix(buf[:], oob)
	_ = uc.SetReadDeadline(time.Time{})
	if err != nil {
		return nil, err
	}
	if oobn == 0 {
		return nil, fmt.Errorf("missing SCM_RIGHTS control message")
	}
	scms, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return nil, fmt.Errorf("ParseSocketControlMessage: %w", err)
	}
	for _, scm := range scms {
		fds, err := syscall.ParseUnixRights(&scm)
		if err != nil || len(fds) == 0 {
			continue
		}
		fd := fds[0]
		for _, extra := range fds[1:] {
			_ = syscall.Close(extra)
		}
		syscall.CloseOnExec(fd)
		_ = syscall.SetNonblock(fd, true)
		return os.NewFile(uintptr(fd), "vsock-tunnel"), nil
	}
	return nil, fmt.Errorf("no FD in SCM_RIGHTS")
}

func serveTunnel(conn io.ReadWriteCloser, ep *channel.Endpoint) {
	defer conn.Close()
	log.Printf("vmnet tunnel connected (dual-stack v4+v6)")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	bw := bufio.NewWriterSize(conn, 131072)

	// Netstack -> Guest: block for first packet, drain any queued burst, then flush once
	go func() {
		var hdr [2]byte
		writePkt := func(pkt *stack.PacketBuffer) error {
			view := pkt.ToView()
			data := view.AsSlice()
			pkt.DecRef()
			if len(data) == 0 || len(data) > 65535 {
				return nil
			}
			binary.LittleEndian.PutUint16(hdr[:], uint16(len(data)))
			if _, err := bw.Write(hdr[:]); err != nil {
				return err
			}
			_, err := bw.Write(data)
			return err
		}

		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			err := writePkt(pkt)
			for batch := 1; err == nil && batch < maxBatch; batch++ {
				next := ep.Read()
				if next == nil {
					break
				}
				err = writePkt(next)
			}
			if err == nil {
				err = bw.Flush()
			}
			if err != nil {
				_ = conn.Close()
				return
			}
		}
	}()

	// Guest -> Netstack
	br := bufio.NewReaderSize(conn, 131072)
	var hdr [2]byte
	for {
		if _, err := io.ReadFull(br, hdr[:]); err != nil {
			break
		}
		plen := int(binary.LittleEndian.Uint16(hdr[:]))
		if plen == 0 || plen > 65535 {
			break
		}
		raw := make([]byte, plen)
		if _, err := io.ReadFull(br, raw); err != nil {
			break
		}
		ver := raw[0] >> 4
		var proto tcpip.NetworkProtocolNumber
		if ver == 4 {
			proto = header.IPv4ProtocolNumber
		} else if ver == 6 {
			proto = header.IPv6ProtocolNumber
		} else {
			continue
		}
		pb := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(raw),
		})
		ep.InjectInbound(proto, pb)
		pb.DecRef()
	}
	cancel()
	log.Printf("vmnet tunnel disconnected")
}

func main() {
	sockPath := flag.String("sock", "", "Unix domain socket path for vsock FD handoff")
	flag.StringVar(&dnsFilePath, "dns-file", "", "Path to file containing active Android DNS servers")
	flag.Parse()

	if *sockPath == "" {
		log.Fatal("-sock is required")
	}
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		os.Exit(0)
	}()

	s, ep, err := createNetstack()
	if err != nil {
		log.Fatalf("netstack init failed: %v", err)
	}
	defer s.Close()

	_ = os.Remove(*sockPath)
	ln, err := net.Listen("unix", *sockPath)
	if err != nil {
		log.Fatalf("listen unix %s: %v", *sockPath, err)
	}
	_ = os.Chmod(*sockPath, 0600)
	defer ln.Close()
	log.Printf("vmnet-host listening on %s (dns-file=%s)", *sockPath, dnsFilePath)

	uln := ln.(*net.UnixListener)
	for {
		uc, err := uln.AcceptUnix()
		if err != nil {
			log.Printf("accept error: %v", err)
			continue
		}
		vf, err := receiveVsockFile(uc)
		if err != nil {
			log.Printf("vsock FD handoff error: %v", err)
			_ = uc.Close()
			continue
		}
		done := make(chan struct{})
		go func() {
			var b [1]byte
			_, _ = uc.Read(b[:])
			select {
			case <-done:
			default:
				_ = vf.Close()
			}
		}()
		serveTunnel(vf, ep)
		close(done)
		_ = uc.Close()
	}
}
