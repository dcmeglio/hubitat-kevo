# hubitat-kevo
Kevo Plus Integration for Hubitat. This currently works, however Kevo seems to be adding new security features such as reCaptcha that could break it at any time
 
## Devices
You must install the device driver for the lock for this to work.
* Kevo Lock

## Apps
The Kevo Plus Integration app is what actually communicates with the Kevo Plus device. You must have a Kevo Plus, not just Kevo locks. This makes the integration a bit slow since it is cloud based.

### Configuration
To connect to the API you will need to specify your Kevo Plus username and password. You will need to get your lock IDs. There is currently no way I have found to get this via the API. You have to go to www.mykevo.com and login. Right click on one of the login icons and choose inspect element. You'll see something like

```html
<div class="rpu-button" data-percent="100" data-lock-id="*2ea69d00-ddd6-4c91-450e-9b3e73e7623e*" data-name="Garage Door" data-type="lock">
```
The highlighted portion is the lock ID. Enter this into the app.