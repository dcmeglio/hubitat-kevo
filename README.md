# hubitat-kevo
Kevo Plus Integration for Hubitat. This currently works, however Kevo seems to be adding new security features such as reCaptcha that could break it at any time
 
## Devices
You must install the device driver for the lock for this to work.
* Kevo Lock

## Apps
The Kevo Plus Integration app is what actually communicates with the Kevo Plus device. You must have a Kevo Plus, not just Kevo locks. This makes the integration a bit slow since it is cloud based.

### Configuration
To connect to the API you will need to specify your Kevo Plus username and password. You will then see the list of available locks for your account. Choose all thatyou wish to integrate with Hubitat.

## Limitations
Kevo has some limitations on how quickly it responds to commands. As a result the integration queues up the commands and executes them as quickly as Kevo will allow. This can result in somne slowness.