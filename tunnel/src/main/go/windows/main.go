//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

// paramnezia-service is the Windows half of the Paramnezia tunnel: a small system service that
// owns the Wintun adapter, runs amneziawg-go with the shared DNS filter, sets addresses, routes and
// DNS, and answers the Paramnezia app over a local named pipe. The app itself runs without
// administrator rights; the installer registers this service once.
//
//	paramnezia-service install     register and start the service (installer, as administrator)
//	paramnezia-service uninstall   stop and remove it (uninstaller)
//	paramnezia-service run         what the service control manager starts
//	paramnezia-service debug       run in a console, for development
//
// It never starts a tunnel on its own: only the app asks for one, over the pipe.
package main

import (
	"fmt"
	"os"
)

const (
	serviceName = "ParamneziaTunnel"
	displayName = "Paramnezia VPN"
	description = "Туннель AmneziaWG для приложения Paramnezia. Включается только из приложения."
	pipeName    = `\\.\pipe\paramnezia`
	adapterName = "Paramnezia"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: paramnezia-service install|uninstall|run|debug")
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "install":
		err = installService()
	case "uninstall":
		err = uninstallService()
	case "run":
		err = runService()
	case "debug":
		err = runDebug()
	default:
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
