/**
 *  Kevo Integration
 *
 *  Copyright 2019 Dominick Meglio
 *
 */

metadata {
    // Automatically generated. Make future change here.
    definition(name: "Kevo Lock", namespace: "dcm.kevo", author: "dmeglio@gmail.com") {
        capability "Lock"
		capability "Refresh"
    }
}

def lock() {
    parent.handleLock(device, device.deviceNetworkId.split(":")[1])
}

def unlock() {
    parent.handleUnlock(device, device.deviceNetworkId.split(":")[1])
}

def refresh() {
	parent.handleRefresh(device, device.deviceNetworkId.split(":")[1])
}