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


preferences {
	page(name: "prefAccountAccess", title: "Kevo")
	page(name: "prefDevices", title: "Kevo")
}

def prefAccountAccess() {
	return dynamicPage(name: "prefApiAccess", title: "Connect to Kevo Plus", nextPage: "prefDevices", uninstall:false, install: false) {
		section("Kevo Login Information"){
			input("kevoUsername", "text", title: "Kevo Username", description: "Enter your Kevo username", required: true)
			input("kevoPassword", "password", title: "Kevo Password", description: "Enter your Kevo password", required: true)
			input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
		}
	}
}

def prefDevices() {
    return dynamicPage(name: "prefDevices", title: "Lock Information", install: true, uninstall: true) {
        section("Lock Information") {
            input(name: "lockCount", type: "number", title: "How many locks do you have?", required: true, submitOnChange: true)
            for (def i = 0; i < lockCount; i++) {
                input(name: "lockName${i}", type: "text", title: "Lock ${i+1} Name", required: true)
                input(name: "lockId${i}", type: "text", title: "Lock ${i+1} ID", required: true)
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
	cleanupChildDevices()
	createChildDevices()
	cleanupSettings()
	schedule("0/30 * * * * ? *", updateDevices)
}

def getTokenInfo() {
	logDebug "Getting token and cookie"
	
	extractTokenAndCookie(sendCommand("/login", "GET", "text/html", "text/html", null, null, true))
}

def extractTokenAndCookie(response) {
    try {
        state.cookie = response?.headers?.'Set-Cookie'?.split(';')?.getAt(0) ?: state.cookie ?: state.cookie   
        state.token = (response.data.text =~ /meta content="(.*?)" name="csrf-token"/)[0][1]
        state.tokenRefresh = now()
    } catch (Exception e) {
		// 
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

	def resp = sendCommand("/signin", "POST", "application/x-www-form-urlencoded", "text/html", body, null, true)
      
	def returnValue = false
	if (resp.status == 302 || resp.status == 200) {
		returnValue = true
		extractTokenAndCookie(resp)
	}

    return returnValue
}

def getLockInfo()
{
	def resp = sendCommand("/user/locks", "GET", "text/html", "text/html", null, null, true)
	if (resp.status == 302 || resp.status == 200)
		extractTokenAndCookie(resp)
}

def getLockStatus(lockId) {
	def query = ['arguments': lockId]
	def resp = sendCommand("/user/remote_locks/command/lock.json", "GET", "application/json", "application/json", null, query, false)
    
	if (resp.status == 200)
		return resp.data
	else
		return null
}

def updateDevices()
{   
    getLockInfo()
    
    for (def i = 0; i < lockCount; i++) {
        updateLockStatus(this.getProperty("lockId${i}"))
    }
}

def updateLockStatus(lockId)
{
    def lockData = getLockStatus(lockId)
	if (lockData == null) 
	{
		device.sendEvent(name: "lock", value: "unknown")
		log.error "Failed to get lock information for ${lockId}"
		return
	}
	logDebug "Got lock state ${lockData.bolt_state}"
    def device = getChildDevice("kevo:" + lockId)
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
    for (def i = 0; i < lockCount; i++) {
        if (!getChildDevice("kevo:" + this.getProperty("lockId${i}")))
            addChildDevice("dcm.kevo", "Kevo Lock", "kevo:" + this.getProperty("lockId${i}"), 1234, ["name": this.getProperty("lockName${i}"), isComponent: false])
    }
}

def cleanupChildDevices()
{
	for (device in getChildDevices())
	{
		def deviceId = device.deviceNetworkId.replace("kevo:","")
		
		def deviceFound = false
		for (def i = 0; i < lockCount; i++)
		{
			def lockId = this.getProperty("lockId${i}")
			if (deviceId == lockId)
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

def cleanupSettings()
{
	def allProperties = this.settings
	def counter = null
	for (property in allProperties) {
		if (property.key.startsWith("lockId")) {
			counter = property.key.replace("lockId","")
			
			if (counter.toInteger() > lockCount)
				app.removeSetting(property.key)
		}
		else if (property.key.startsWith("lockName")) {
			counter = property.key.replace("lockName","")
			if (counter.toInteger() > lockCount)
				app.removeSetting(property.key)
		}
	}
}

def getHeaders() {
    def headers = [
            "Cookie"       : state.cookie,
            "User-Agent"   : "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:52.0) Gecko/20100101 Firefox/52.0",
            "Connection"   : "keep-alive",
            "Cache-Control": "no-cache"
    ]
    if (state.token) {
        headers["Referer"] = state.referer ?: "https://mykevo.com/login"
        headers["X-CSRF-TOKEN"] = state.token
    }
    return headers
}

def sendCommand(path, method, requestType, contentType, body, query, textParer)
{
    def result = sendCommandRaw(path, method, requestType, contentType, body, query, textParser)
    if (result.status >= 400)
    {
        logDebug "Received an error, attempting to relogin"
        if (!login())
		{
			getTokenInfo()
			login()
		}
		result = sendCommandRaw(path, method, requestType, contentType, body, query, textParser)
		if (result.status >= 400)
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
	logDebug "Request path:${path} method:${method} body:${params.body} query:${query}"
	def result = null
	if (method == "GET")
	{
		httpGet(params) { resp -> 
			result = resp
		}
	}
	else if (method == "POST")
	{
		httpPost(params) { resp ->
			result = resp
			
		}
	}
	logDebug "Response ${result.status}"
	state.referer = "${params['uri']}${params['path']}"
	return result
}

def handleLock(device, id) {
	unschedule(updateDevices)
	def currentValue = device.currentValue("lock")
	logDebug "current lock state is ${currentValue}"
    sendCommand("/user/remote_locks/command/remote_lock.json", "GET", "application/json", "application/json", null, ['arguments': id], false)
	pauseExecution(2000)
	if (currentValue != "locked")
	{
		for (def i = 0; i < 3; i++)
		{
		logDebug "Checking lock status..."
			pauseExecution(5000)
			def newLockStatus = updateLockStatus(id)
			if (newLockStatus != "unknown" && newLockStatus != currentValue)
				break
		}
	}
	schedule("0/30 * * * * ? *", updateDevices)
}

def handleUnlock(device, id)  {
	unschedule(updateDevices)
	def currentValue = device.currentValue("lock")
	logDebug "current lock state is ${currentValue}"
    sendCommand("/user/remote_locks/command/remote_unlock.json", "GET", "application/json", "application/json", null, ['arguments': id], false)
	pauseExecution(2000)
	if (currentValue != "unlocked")
	{
		for (def i = 0; i < 3; i++)
		{
			logDebug "Checking lock status..."
			pauseExecution(5000)
			def newLockStatus = updateLockStatus(id)
			if (newLockStatus != "unknown" && newLockStatus != currentValue)
				break
		}
	}
    schedule("0/30 * * * * ? *", updateDevices)
}

def handleRefresh(device, id) {
	unschedule(updateDevices)
	updateLockStatus(id)
	schedule("0/30 * * * * ? *", updateDevices)
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}