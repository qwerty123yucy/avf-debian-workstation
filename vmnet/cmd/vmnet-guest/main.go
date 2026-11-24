package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"sync"
	"syscall"
	"unsafe"
)

const (
	afVsock        = 40
	vmaddrCidAny   = 0xFFFFFFFF
	tunsetiffIoctl = 0x400454ca
	iffTun         = 0x0001
	iffNoPi        = 0x1000
	maxBatch       = 64
)

type ifReq struct {
	Name  [16]byte
	Flags uint16
	_     [22]byte
}

type rawSockaddrVM struct {
	Family   uint16
	Reserved uint16
	Port     uint32
	CID      uint32
	Flags    uint8
	Zero     [3]uint8
}

// openTun configures /dev/net/tun on raw FD before wrapping with os.NewFile
// so O_NONBLOCK stays set and Go registers the TUN device with its epoll netpoller.
func openTun(name string) (*os.File, syscall.RawConn, error) {
	fd, err := syscall.Open("/dev/net/tun", syscall.O_RDWR|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, nil, err
	}
	var req ifReq
	copy(req.Name[:], name)
	req.Flags = iffTun | iffNoPi
	_, _, errno := syscall.Syscall(
		syscall.SYS_IOCTL,
		uintptr(fd),
		uintptr(tunsetiffIoctl),
		uintptr(unsafe.Pointer(&req)),
	)
	if errno != 0 {
		_ = syscall.Close(fd)
		return nil, nil, fmt.Errorf("TUNSETIFF %s: %v", name, errno)
	}
	if err := syscall.SetNonblock(fd, true); err != nil {
		_ = syscall.Close(fd)
		return nil, nil, fmt.Errorf("SetNonblock(%s): %w", name, err)
	}
	f := os.NewFile(uintptr(fd), "/dev/net/tun")
	rc, err := f.SyscallConn()
	if err != nil {
		_ = f.Close()
		return nil, nil, fmt.Errorf("SyscallConn(%s): %w", name, err)
	}
	return f, rc, nil
}

func tryReadTunNonblock(rc syscall.RawConn, buf []byte) int {
	got := 0
	_ = rc.Read(func(fd uintptr) bool {
		n, err := syscall.Read(int(fd), buf)
		if err == nil && n > 0 {
			got = n
		}
		return true
	})
	return got
}

func runCmd(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %v: %v (%s)", name, args, err, string(out))
	}
	return nil
}

func setupGuestNetwork(ifName, guestCIDR, guestCIDR6, dnsIP1, dnsIP2 string) error {
	_ = os.WriteFile("/proc/sys/net/ipv6/conf/"+ifName+"/disable_ipv6", []byte("0\n"), 0644)
	if err := runCmd("ip", "link", "set", "dev", ifName, "mtu", "1500", "up"); err != nil {
		return err
	}
	_ = runCmd("ip", "-4", "addr", "flush", "dev", ifName)
	if err := runCmd("ip", "-4", "addr", "add", guestCIDR, "dev", ifName); err != nil {
		return err
	}
	if err := runCmd("ip", "-4", "route", "replace", "default", "dev", ifName); err != nil {
		return err
	}
	if guestCIDR6 != "" {
		_ = runCmd("ip", "-6", "addr", "flush", "dev", ifName, "scope", "global")
		if err := runCmd("ip", "-6", "addr", "add", guestCIDR6, "dev", ifName, "nodad"); err != nil {
			return err
		}
		if err := runCmd("ip", "-6", "route", "replace", "default", "dev", ifName); err != nil {
			return err
		}
	}
	resolv := fmt.Sprintf("# Managed by vmnet-guest (dual-stack routed via Android VpnService)\nnameserver %s\n", dnsIP1)
	if dnsIP2 != "" && dnsIP2 != dnsIP1 {
		resolv += fmt.Sprintf("nameserver %s\n", dnsIP2)
	}
	resolv += "options timeout:1 attempts:2\n"
	tmpResolv := "/etc/resolv.conf.tmp"
	if err := os.WriteFile(tmpResolv, []byte(resolv), 0644); err != nil {
		return err
	}
	return os.Rename(tmpResolv, "/etc/resolv.conf")
}

func listenVsock(port uint32) (int, error) {
	fd, err := syscall.Socket(afVsock, syscall.SOCK_STREAM, 0)
	if err != nil {
		return -1, fmt.Errorf("socket(AF_VSOCK): %w", err)
	}
	syscall.CloseOnExec(fd)
	sa := rawSockaddrVM{
		Family: afVsock,
		Port:   port,
		CID:    vmaddrCidAny,
	}
	_, _, errno := syscall.Syscall(
		syscall.SYS_BIND,
		uintptr(fd),
		uintptr(unsafe.Pointer(&sa)),
		unsafe.Sizeof(sa),
	)
	if errno != 0 {
		_ = syscall.Close(fd)
		return -1, fmt.Errorf("bind(vsock:%d): %v", port, errno)
	}
	if err := syscall.Listen(fd, 4); err != nil {
		_ = syscall.Close(fd)
		return -1, fmt.Errorf("listen(vsock:%d): %w", port, err)
	}
	return fd, nil
}

func acceptVsock(lfd int) (int, error) {
	var sa rawSockaddrVM
	saLen := uint32(unsafe.Sizeof(sa))
	nfd, _, errno := syscall.Syscall(
		syscall.SYS_ACCEPT,
		uintptr(lfd),
		uintptr(unsafe.Pointer(&sa)),
		uintptr(unsafe.Pointer(&saLen)),
	)
	if errno != 0 {
		return -1, errno
	}
	fd := int(nfd)
	syscall.CloseOnExec(fd)
	_ = syscall.SetNonblock(fd, true)
	return fd, nil
}

func main() {
	ifName := flag.String("if", "vmnet0", "TUN interface name")
	cidr := flag.String("cidr", "10.0.2.2/24", "Guest IPv4 CIDR")
	cidr6 := flag.String("cidr6", "fd00:a7f::2/64", "Guest IPv6 ULA CIDR")
	dnsIP := flag.String("dns", "10.0.2.3", "Primary virtual DNS IP")
	dnsIP2 := flag.String("dns2", "10.0.2.4", "Secondary virtual DNS IP")
	port := flag.Uint("port", 2223, "VSOCK listen port")
	flag.Parse()

	tun, tunRaw, err := openTun(*ifName)
	if err != nil {
		log.Fatalf("openTun: %v", err)
	}
	defer tun.Close()

	if err := setupGuestNetwork(*ifName, *cidr, *cidr6, *dnsIP, *dnsIP2); err != nil {
		log.Fatalf("setupGuestNetwork: %v", err)
	}
	log.Printf("Configured %s (v4=%s, v6=%s, dns=%s,%s)", *ifName, *cidr, *cidr6, *dnsIP, *dnsIP2)

	lfd, err := listenVsock(uint32(*port))
	if err != nil {
		log.Fatalf("listenVsock: %v", err)
	}
	defer syscall.Close(lfd)
	log.Printf("Listening on AF_VSOCK port %d", *port)

	var (
		connMu sync.Mutex
		active *os.File
		bw     *bufio.Writer
	)

	// TUN -> VSOCK goroutine: block for first packet, drain queued burst, flush once
	go func() {
		buf := make([]byte, 65536)
		var hdr [2]byte
		writeFrame := func(w *bufio.Writer, pkt []byte) error {
			binary.LittleEndian.PutUint16(hdr[:], uint16(len(pkt)))
			if _, err := w.Write(hdr[:]); err != nil {
				return err
			}
			_, err := w.Write(pkt)
			return err
		}

		for {
			n, err := tun.Read(buf)
			if err != nil {
				if errors.Is(err, syscall.EINTR) {
					continue
				}
				log.Fatalf("tun read fatal: %v", err)
			}
			if n <= 0 || n > 65535 {
				continue
			}
			connMu.Lock()
			if bw != nil {
				wErr := writeFrame(bw, buf[:n])
				for batch := 1; wErr == nil && batch < maxBatch; batch++ {
					n2 := tryReadTunNonblock(tunRaw, buf)
					if n2 <= 0 || n2 > 65535 {
						break
					}
					wErr = writeFrame(bw, buf[:n2])
				}
				if wErr == nil {
					wErr = bw.Flush()
				}
				if wErr != nil {
					_ = active.Close()
					active = nil
					bw = nil
				}
			}
			connMu.Unlock()
		}
	}()

	// Accept loop for host connections
	for {
		cfd, err := acceptVsock(lfd)
		if err != nil {
			log.Printf("accept error: %v", err)
			continue
		}
		cf := os.NewFile(uintptr(cfd), "vsock-conn")
		log.Printf("Host vmnet connected")

		connMu.Lock()
		if active != nil {
			_ = active.Close()
		}
		active = cf
		bw = bufio.NewWriterSize(cf, 131072)
		connMu.Unlock()

		// VSOCK -> TUN loop
		br := bufio.NewReaderSize(cf, 131072)
		var hdr [2]byte
		pkt := make([]byte, 65536)
		for {
			if _, err := io.ReadFull(br, hdr[:]); err != nil {
				break
			}
			plen := int(binary.LittleEndian.Uint16(hdr[:]))
			if plen == 0 || plen > len(pkt) {
				break
			}
			if _, err := io.ReadFull(br, pkt[:plen]); err != nil {
				break
			}
			_, _ = tun.Write(pkt[:plen])
		}

		connMu.Lock()
		if active == cf {
			active = nil
			bw = nil
		}
		connMu.Unlock()
		_ = cf.Close()
		log.Printf("Host vmnet disconnected")
	}
}
