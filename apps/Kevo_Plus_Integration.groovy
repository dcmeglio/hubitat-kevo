/**
 *  Kevo Plus Integration
 *
 *  Copyright 2019 Dominick Meglio
 *
 */
definition(
    name: "Kevo Plus Integration",
    namespace: "dcm.kevo",
    author: "Dominick Meglio",
    description: "Integrate your Kevo Smart Locks with Hubitat",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")

import groovy.transform.Field
@Field static java.util.concurrent.ConcurrentLinkedQueue commandQueue = new java.util.concurrent.ConcurrentLinkedQueue()

@Field static java.util.concurrent.Semaphore mutex = new java.util.concurrent.Semaphore(1)

preferences {
	page(name: "prefAccountAccess", title: "Kevo")
	page(name: "prefDevices", title: "Kevo")
}

def prefAccountAccess() {
	getTokenInfo()
	return dynamicPage(name: "prefApiAccess", title: "Connect to Kevo Plus", nextPage: "prefDevices", uninstall:false, install: false) {
		section("Kevo Login Information"){
			input("kevoUsername", "text", title: "Kevo Username", description: "Enter your Kevo username", required: true)
			input("kevoPassword", "password", title: "Kevo Password", description: "Enter your Kevo password", required: true)
			input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
		}
	}
}

def prefDevices() {
	if (login())
	{
        getLocksList()
		return dynamicPage(name: "prefDevices", title: "Lock Information", install: true, uninstall: true) {
			section("Lock Information") {
				input(name: "locks", type: "enum", title: "Locks", required:false, multiple:true, options:state.lockList, hideWhenEmpty: true)
			}
		}
	}
}

def installed() {
	logDebug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	logDebug "Updated with settings: ${settings}"
	unschedule()
	unsubscribe()
	initialize()
}

def uninstalled() {
	logDebug "uninstalling app"
	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}
}

def initialize() {
	logDebug "initializing"
	
	state.lastLockQuery = 0
	cleanupChildDevices()
	createChildDevices()
	schedule("0/1 * * * * ?", runAllActions)
}

def runAllActions()
{
	try
	{
		if (!mutex.tryAcquire())
		{
			// Bust the lock if it is too old, indicates an issue with Hubitat eventing
			if ((now() - state.lockTime ?: 0) > 120000) {
				
				log.warn "Lock was held for 2 minutes, releasing ${now()} ${state.lockTime} ${now() - state.lockTime}"
				mutex.release()
			}
		
			return
		}
		state.lockTime = now()
		def action = null
		while ((action = commandQueue.poll()) != null)
		{
			logDebug "Executing ${action.command} on ${action.id} ${commandQueue.size()}"
			if (action.command == "lock")
				executeLock(action.id)
			else if (action.command == "unlock")
				executeUnlock(action.id)
			else if (action.command == "refresh")
				executeRefresh()
		}
		if (now() - state.lastLockQuery >= 30000)
		{
			logDebug "Updating devices"
			state.lastLockQuery = now()
			updateDevices()
		}
		mutex.release()
	}
	catch (e)
	{
		mutex.release()
		log.error e
	}
}

def getLocksList()
{
	state.lockList = [:]
    def result = sendCommand("/user/locks", "GET", "application/json", "text/html", null, null, true)
    def html = result.data.text
	def matches = (html =~ /data-lock-id="(.*?)" data-name="(.*?)" data-type="lock">/)
	log.debug "matches: ${matches.size()}"
	for (def i = 0; i < matches.size(); i++)
	{
		state.lockList[matches[i][1]] = matches[i][2]
	}
}

def executeLock(id) {
	for (def i = 0; i < 3; i++)
	{
		if (executeLockOrUnlock(id, "lock"))
			return true
	}
	return false
}

def executeUnlock(id) {
	for (def i = 0; i < 3; i++)
	{
		if (executeLockOrUnlock(id, "unlock"))
			return true
	}
	return false
}

def executeLockOrUnlock(id, action)
{
	def lockState = "locked"
	if (action == "unlock")
		lockState = "unlocked"
	sendCommand("/user/remote_locks/command/remote_${action}.json", "GET", "application/json", "application/json", null, ['arguments': id], false)
	pauseExecution(2000)
	
	for (def i = 0; i < 3; i++)
	{
		logDebug "Checking lock status..."
		pauseExecution(5000)
		def newLockStatus = updateLockStatus(id)
		if (newLockStatus != "unknown" && newLockStatus == lockState)
			return true
	}
	return false
}

def executeRefresh() {
	updateDevices()
}

def getTokenInfo() {
	logDebug "Getting token and cookie"
	state.token = null
	state.cookie = null
	
	extractTokenAndCookie(sendCommand("/login", "GET", "text/html", "text/html", null, null, true))
}

def extractTokenAndCookie(response) {
    try {
		logDebug "Got token response of ${response.status}"
        state.cookie = response?.headers?.'Set-Cookie'?.split(';')?.getAt(0) ?: state.cookie ?: state.cookie   
		logDebug "Got cookie ${state.cookie}"
        state.token = (response.data.text =~ /meta content="(.*?)" name="csrf-token"/)[0][1]
		logDebug "Got token ${state.token}"
    } catch (Exception e) {
		logDebug "Token reading threw ${e}" 
    }
}

def login() {
    def body = [
            "user[username]"    : kevoUsername,
            "user[password]"    : kevoPassword,
            "authenticity_token": state.token,
            "commit"            : "LOGIN",
            "utf8"              : "✓"

    ]

	def resp = sendCommand("/signin", "POST", "application/x-www-form-urlencoded", "text/html", body, null, false)
      
	def returnValue = false
	if (resp != null && (resp.status == 302 || resp.status == 200)) {
		returnValue = true
		
	}

    return returnValue
}

def getLockStatus(lockId) {
	try
	{
		def query = ['arguments': lockId]
		def resp = sendCommand("/user/remote_locks/command/lock.json", "GET", "application/json", "application/json", null, query, false)
		
		if (resp != null && resp.status == 200)
			return resp.data
		else
			return null
	}
	catch (e)
	{
	logDebug "${e}"
		return null
	}
}

def updateDevices()
{   
    for (def i = 0; i < lockCount; i++) {
        if (updateLockStatus(this.getProperty("lockId${i}")) == null)
			return
    }
}

def updateLockStatus(lockId)
{
    def lockData = getLockStatus(lockId)
	def device = getChildDevice("kevo:" + lockId)
	if (lockData == null) 
	{
		device.sendEvent(name: "lock", value: "unknown")
		log.error "Failed to get lock information for ${lockId}"
		return null
	}
	logDebug "Got lock state ${lockData.bolt_state}"
    
    if (lockData.bolt_state == "Locked")
    {
        device.sendEvent(name: "lock", value: "locked")
		return "locked"
    }
    else if (lockData.bolt_state == "Unlocked")
    {
        device.sendEvent(name: "lock", value: "unlocked")
		return "unlocked"
    }
    else
    {
        device.sendEvent(name: "lock", value: "unknown")
		return "unknown"
    }
}

def createChildDevices() {
	for (lock in locks)
	{
		if (!getChildDevice("kevo:" + lock))
            addChildDevice("dcm.kevo", "Kevo Lock", "kevo:" + lock, 1234, ["name": state.lockList[lock], isComponent: false])
	}
}

def cleanupChildDevices()
{
	for (device in getChildDevices())
	{
		def deviceId = device.deviceNetworkId.replace("kevo:","")
		
		def deviceFound = false
		for (lock in locks)
		{
			if (lock == deviceId)
			{
				deviceFound = true
				break
			}
		}
				
		if (deviceFound == true)
			continue
			
		deleteChildDevice(device.deviceNetworkId)
	}
}

def getHeaders() {
    def headers = [
			"Cookie"	   : state.cookie,
            "User-Agent"   : "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:52.0) Gecko/20100101 Firefox/52.0",
            "Connection"   : "keep-alive",
            "Cache-Control": "no-cache"
    ]
    if (state.token) {
        headers["Referer"] = state.referer ?: "https://www.mykevo.com/login"
        headers["X-CSRF-TOKEN"] = state.token
    }

    return headers
}

def sendCommand(path, method, requestType, contentType, body, query, textParser)
{
	def result = null
	try
	{
		result = sendCommandRaw(path, method, requestType, contentType, body, query, textParser)
		if (result == null || result.status >= 400)
		{
			logDebug "Received an error, attempting to relogin"
			getTokenInfo()
			login()
			result = sendCommandRaw(path, method, requestType, contentType, body, query, textParser)
			if (result != null && result.status >= 400)
				log.error "Error Status: ${result.status} for request ${path} (${response.data})"
		}
	}
	catch (e)
	{
		logDebug "Received an error, attempting to relogin"
		getTokenInfo()
		login()
		result = sendCommandRaw(path, method, requestType, contentType, body, query, textParser)
		if (result != null && result.status >= 400)
			log.error "Error Status: ${result.status} for request ${path} (${response.data})"
	}

	return result
}

def sendCommandRaw(path, method, requestType, contentType, body, query, textParser) {
	def params = [
		uri: "https://www.mykevo.com",
		path: path,
        requestContentType: requestType,
        contentType: contentType,
        headers: headers,
        textParser: textParser
	]
	if (query != null)
		params.query = query
		
	if (body != null)
	{
		def stringBody = body?.collect { k, v -> "$k=$v" }?.join("&")?.toString() ?: ""
		params.body = stringBody
	}
	def result = null
	try
	{
		if (method == "GET")
		{
			httpGet(params) { resp -> 
				state.cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0) ?: state.cookie ?: state.cookie   
				result = resp
			}
		}
		else if (method == "POST")
		{
			httpPost(params) { resp ->
				state.cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0) ?: state.cookie ?: state.cookie   
				result = resp
			}
		}
		state.referer = "${params['uri']}${params['path']}"
	}
	catch (e)
	{
		logDebug "Exception ${e}"
	
	}

	return result
}

def handleLock(lockDevice, id) {
	logDebug "Queued lock for ${id} ${commandQueue.size()}"
	commandQueue.offer([command: "lock", id: id])
}

def handleUnlock(lockDevice, id) {
	logDebug "Queued unlock for ${id} ${commandQueue.size()}"
	commandQueue.offer([command: "unlock", id: id])
}

def handleRefresh(lockDevice, id) {
	commandQueue.offer([command: "refresh", id: id])
	logDebug "Queued refresh for ${id} ${commandQueue.size()}"
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}