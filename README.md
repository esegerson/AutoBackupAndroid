# Android Auto Backup
This is a small utility that automatically backs up 
files from your phone to your local desktop PC via
SMB (simple fileshare).  The idea is to have a
frictionless backup solution that does not rely on
cloud providers like Google or Microsoft.

## How to Set Up in Android Studio

### local.properties

Add `local.properties` file to root with this format:

    sdk.dir=C\:\\Users\\[UserName]\\AppData\\Local\\Android\\Sdk
    SMB_HOST=192.168.1.2
    SMB_USERNAME="********"
    SMB_PASSWORD="********"
    TARGET_WIFI_SSIDS=********,********

Replace `[Username]` with your actual username.

Replace the SMB username and password with the 
user/pass of your PC login.

Specify the local IP of your PC.

Specify the wifi name of your home network. If you 
have more than one, separate them with a comma. 

### Home PC

Create a folder on your PC's hard drive (ideally a
large drive for backups).  Example:  `F:\Backups\Android`
This folder is referenced by `BackupConfig.kt` 
`SMB_SHARE_NAME` and `SMB_SUBFOLDER`.

Go into the properties of the parent `Backup` folder
and share it.

### Further Configuration

Inspect `BackupConfig.kt` to see if there are any
other values that you want to change, such as the
scheduled hour or SMB share folder.

## How to Run

On your Android phone, allow USB debugging.

Plug in your phone to the PC.

From Android Studio, run the app on the phone.  This 
will install it to the phone.

Once installed, run the application once to schedule 
the service.  From then on, you can forget about it.
Grant the requested permissions (which are several)
so it can read the wifi network name and read files 
on your phone.

The app will run once per day at the hardcoded time
(8 PM) only if you are connected to your home
wifi.  If you are not at home at that time, it'll
try again the next day.  Files that already exist
on the SMB share are not copied again.