//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

func installService() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("service manager (run as administrator): %w", err)
	}
	defer m.Disconnect()
	cfg := mgr.Config{
		ServiceType:      windows.SERVICE_WIN32_OWN_PROCESS,
		StartType:        mgr.StartAutomatic,
		DelayedAutoStart: true,
		ErrorControl:     mgr.ErrorNormal,
		DisplayName:      displayName,
		Description:      description,
		SidType:          windows.SERVICE_SID_TYPE_UNRESTRICTED,
	}
	s, err := m.OpenService(serviceName)
	if err == nil {
		// Upgrade: same service, new binary path and settings.
		stopAndWait(s)
		cfg.BinaryPathName = fmt.Sprintf(`"%s" run`, exe)
		if err := s.UpdateConfig(cfg); err != nil {
			s.Close()
			return err
		}
	} else {
		s, err = m.CreateService(serviceName, exe, cfg, "run")
		if err != nil {
			return err
		}
	}
	defer s.Close()
	// Restart after a crash: 1 s, 5 s, then every 30 s; the failure count resets daily.
	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: time.Second},
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 30 * time.Second},
	}, 86400)
	return s.Start()
}

func uninstallService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(serviceName)
	if err != nil {
		return nil // already gone
	}
	defer s.Close()
	stopAndWait(s)
	return s.Delete()
}

func stopAndWait(s *mgr.Service) {
	st, err := s.Control(svc.Stop)
	if err != nil {
		return
	}
	for i := 0; i < 50 && st.State != svc.Stopped; i++ {
		time.Sleep(200 * time.Millisecond)
		if st, err = s.Query(); err != nil {
			return
		}
	}
}

type handler struct{}

func (handler) Execute(_ []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	srv, err := startServer()
	if err != nil {
		log.Printf("start: %v", err)
		return true, 1
	}
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for c := range req {
		switch c.Cmd {
		case svc.Interrogate:
			status <- c.CurrentStatus
		case svc.Stop, svc.Shutdown:
			status <- svc.Status{State: svc.StopPending}
			srv.close()
			return false, 0
		}
	}
	return false, 0
}

func runService() error {
	setupLog()
	log.Printf("Paramnezia service starting")
	return svc.Run(serviceName, handler{})
}

func runDebug() error {
	log.SetOutput(os.Stderr)
	srv, err := startServer()
	if err != nil {
		return err
	}
	log.Printf("listening on %s; Ctrl+C to stop", pipeName)
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt)
	<-ch
	srv.close()
	return nil
}

// setupLog writes to %ProgramData%\Paramnezia\service.log, starting over past 1 MB.
func setupLog() {
	dir := filepath.Join(os.Getenv("ProgramData"), "Paramnezia")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	path := filepath.Join(dir, "service.log")
	if fi, err := os.Stat(path); err == nil && fi.Size() > 1<<20 {
		_ = os.Rename(path, path+".1")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(f))
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
}

var errNotUp = errors.New("tunnel is not running")
