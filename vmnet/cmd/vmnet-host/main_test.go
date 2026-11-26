package main

import (
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
)

func buildIPv4UDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	udpLen := header.UDPMinimumSize + len(payload)
	totalLen := header.IPv4MinimumSize + udpLen
	pkt := make([]byte, totalLen)

	ip := header.IPv4(pkt)
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.UDPProtocolNumber),
		SrcAddr:     tcpip.AddrFromSlice(srcIP.To4()),
		DstAddr:     tcpip.AddrFromSlice(dstIP.To4()),
	})
	ip.SetChecksum(^ip.CalculateChecksum())

	udp := header.UDP(pkt[header.IPv4MinimumSize:])
	udp.Encode(&header.UDPFields{
		SrcPort: srcPort,
		DstPort: dstPort,
		Length:  uint16(udpLen),
	})
	copy(pkt[header.IPv4MinimumSize+header.UDPMinimumSize:], payload)

	xsum := header.PseudoHeaderChecksum(
		header.UDPProtocolNumber,
		tcpip.AddrFromSlice(srcIP.To4()),
		tcpip.AddrFromSlice(dstIP.To4()),
		uint16(udpLen),
	)
	xsum = checksum.Checksum(payload, xsum)
	udp.SetChecksum(^udp.CalculateChecksum(xsum))
	return pkt
}

func writeTunnelFrame(w io.Writer, pkt []byte) error {
	var hdr [2]byte
	binary.LittleEndian.PutUint16(hdr[:], uint16(len(pkt)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err := w.Write(pkt)
	return err
}

func readTunnelFrame(r io.Reader) ([]byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := int(binary.LittleEndian.Uint16(hdr[:]))
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func tcpConnPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen tcp4: %v", err)
	}
	defer ln.Close()
	ch := make(chan net.Conn, 1)
	go func() {
		c, _ := ln.Accept()
		ch <- c
	}()
	c1, err := net.Dial("tcp4", ln.Addr().String())
	if err != nil {
		t.Fatalf("Dial tcp4: %v", err)
	}
	c2 := <-ch
	if c2 == nil {
		c1.Close()
		t.Fatal("Accept tcp4 failed")
	}
	return c1, c2
}

func udpConnPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	p1, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatalf("ListenUDP 1: %v", err)
	}
	p2, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		p1.Close()
		t.Fatalf("ListenUDP 2: %v", err)
	}
	a1 := p1.LocalAddr().(*net.UDPAddr)
	a2 := p2.LocalAddr().(*net.UDPAddr)
	_ = p1.Close()
	_ = p2.Close()

	c1, err := net.DialUDP("udp4", a1, a2)
	if err != nil {
		t.Fatalf("DialUDP 1: %v", err)
	}
	c2, err := net.DialUDP("udp4", a2, a1)
	if err != nil {
		c1.Close()
		t.Fatalf("DialUDP 2: %v", err)
	}
	return c1, c2
}

func pickLocalOutboundIPv4() net.IP {
	// Avoid net.Interfaces()/net.InterfaceAddrs() because Android SELinux blocks
	// unprivileged RTM_GETADDR netlink dumps in Go's netlink RIB reader.
	c, err := net.Dial("udp4", "8.8.8.8:53")
	if err != nil {
		return nil
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok || ua.IP == nil || ua.IP.IsLoopback() {
		return nil
	}
	return ua.IP.To4()
}

func TestRelayUDPDNSDualStackAndPostReplyExpiry(t *testing.T) {
	upSrv, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	defer upSrv.Close()

	go func() {
		buf := make([]byte, 4096)
		for {
			n, peer, err := upSrv.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := append([]byte(nil), buf[:n]...)
			if string(msg) == "QUERY-AAAA" {
				go func(p net.Addr, m []byte) {
					time.Sleep(250 * time.Millisecond)
					_, _ = upSrv.WriteTo([]byte("REPLY-"+string(m)), p)
				}(peer, msg)
			} else {
				_, _ = upSrv.WriteTo([]byte("REPLY-"+string(msg)), peer)
			}
		}
	}()

	gClient, gServer := udpConnPair(t)
	defer gClient.Close()
	defer gServer.Close()

	hostConn, err := net.Dial("udp4", upSrv.LocalAddr().String())
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	defer hostConn.Close()

	t0 := time.Now()
	done := make(chan struct{})
	go func() {
		defer close(done)
		relayUDPConn(gServer, hostConn, true)
	}()

	if _, err := gClient.Write([]byte("QUERY-A")); err != nil {
		t.Fatal(err)
	}
	if _, err := gClient.Write([]byte("QUERY-AAAA")); err != nil {
		t.Fatal(err)
	}

	got := make(map[string]bool)
	buf := make([]byte, 256)
	for i := 0; i < 2; i++ {
		_ = gClient.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := gClient.Read(buf)
		if err != nil {
			t.Fatalf("read reply %d: %v", i, err)
		}
		got[string(buf[:n])] = true
	}
	if !got["REPLY-QUERY-A"] || !got["REPLY-QUERY-AAAA"] {
		t.Fatalf("missing dual-stack replies: %v", got)
	}

	select {
	case <-done:
		dt := time.Since(t0)
		if dt > 3200*time.Millisecond {
			t.Fatalf("expected post-reply DNS idle expiry ~1.75s, took %v", dt)
		}
	case <-time.After(3500 * time.Millisecond):
		t.Fatalf("relayUDPConn(isDNS=true) did not release flow within post-reply window")
	}
}

func TestTunnelReconnectAnd800FlowBurst(t *testing.T) {
	s, ep, err := createNetstack()
	if err != nil {
		t.Fatalf("createNetstack: %v", err)
	}
	defer s.Close()

	srcIP := net.ParseIP("10.0.2.2").To4()
	gwIP := net.ParseIP(gwVirtIP).To4()

	// 1. Rapid 15-round tunnel reconnects must cleanly drain writerWG without hanging
	for round := 0; round < 15; round++ {
		cGuest, cHost := tcpConnPair(t)
		done := make(chan struct{})
		go func() {
			defer close(done)
			serveTunnel(cHost, ep)
		}()
		pkt := buildIPv4UDPPacket(srcIP, gwIP, uint16(20000+round), 9, []byte("ping"))
		if err := writeTunnelFrame(cGuest, pkt); err != nil {
			t.Fatalf("round %d write: %v", round, err)
		}
		_ = cGuest.Close()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatalf("round %d serveTunnel hung on close", round)
		}
	}

	// 2. End-to-end 800-flow concurrent UDP burst (> old 512 limit, within new 2048 limit)
	hostIP := pickLocalOutboundIPv4()
	if hostIP == nil {
		t.Skip("no non-tunnel local IPv4 interface available for end-to-end gVisor UDP burst")
	}
	srvConn, err := net.ListenPacket("udp4", net.JoinHostPort(hostIP.String(), "0"))
	if err != nil {
		t.Skipf("cannot bind %s: %v", hostIP, err)
	}
	defer srvConn.Close()
	if uc, ok := srvConn.(*net.UDPConn); ok {
		_ = uc.SetReadBuffer(2 << 20)
		_ = uc.SetWriteBuffer(2 << 20)
	}
	srvPort := uint16(srvConn.LocalAddr().(*net.UDPAddr).Port)

	go func() {
		buf := make([]byte, 2048)
		for {
			n, peer, err := srvConn.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = srvConn.WriteTo(buf[:n], peer)
		}
	}()

	cGuest, cHost := tcpConnPair(t)
	tunDone := make(chan struct{})
	go func() {
		defer close(tunDone)
		serveTunnel(cHost, ep)
	}()

	var rxMu sync.Mutex
	rxPorts := make(map[uint16]bool)
	rxStop := make(chan struct{})
	go func() {
		defer close(rxStop)
		for {
			raw, err := readTunnelFrame(cGuest)
			if err != nil {
				return
			}
			if len(raw) < header.IPv4MinimumSize+header.UDPMinimumSize || (raw[0]>>4) != 4 {
				continue
			}
			ip := header.IPv4(raw)
			if ip.Protocol() != uint8(header.UDPProtocolNumber) {
				continue
			}
			udp := header.UDP(raw[ip.HeaderLength():])
			rxMu.Lock()
			rxPorts[udp.DestinationPort()] = true
			rxMu.Unlock()
		}
	}()

	const burst = 800
	for i := 0; i < burst; i++ {
		p := buildIPv4UDPPacket(srcIP, hostIP, uint16(32000+i), srvPort, []byte("probe"))
		if err := writeTunnelFrame(cGuest, p); err != nil {
			t.Fatalf("burst write %d: %v", i, err)
		}
		if (i & 31) == 31 {
			time.Sleep(2 * time.Millisecond)
		}
	}

	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		rxMu.Lock()
		got := len(rxPorts)
		rxMu.Unlock()
		if got == burst {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	rxMu.Lock()
	got := len(rxPorts)
	rxMu.Unlock()

	_ = cGuest.Close()
	<-tunDone
	<-rxStop

	if got != burst {
		t.Fatalf("800-flow UDP burst got %d/%d replies", got, burst)
	}
}
