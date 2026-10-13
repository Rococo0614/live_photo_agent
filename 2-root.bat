@echo off
adb wait-for-device
echo =====waitting for port, phone *#558# ,then click [debugging port] to enabled port, but
echo =====if [debugging port] is enabled, Press any key 
pause
adb wait-for-device
adb shell getprop ro.vivo.product.version
set disableVusbd=0
set ftbootDebug=0
for /F %%i in ('adb shell getprop ro.vivo.disable.vusbd') do ( set disableVusbd=%%i)
for /F %%i in ('adb shell getprop ro.vivo.ftboot.debug') do ( set ftbootDebug=%%i)


echo =====root status:%disableVusbd% %ftbootDebug%
if '%disableVusbd%' == '1' (goto:root_fastboot)
if '%disableVusbd%' == '0' ( 
    ::旧root方案，内销root方案
    echo ======root vusbd======
    adb vivoroot
    adb remount
    if %errorlevel% neq 0 (
        echo=
        echo ========firt remount failed,remount again========
        adb wait-for-device
        adb vivoroot
        adb remount
    )
    adb shell setenforce 1
    goto:eof
)
echo do nothing
goto:eof

:root_fastboot
if '%ftbootDebug%' == '1' (goto:newFastbootRoot)
echo ======begin root, pls wait======
adb reboot bootloader
ping 127.0.0.1 -n 5 > nul
fastboot devices
fastboot xroot
fastboot oem remount
fastboot reboot
ping 127.0.0.1 -n 5 > nul
adb wait-for-device
adb root
adb remount
adb reboot remount
echo=
echo =====reboot remount, Waitting for restart, Press any key after restart......
echo =====while Restart completed, waitting for port, phone *#558# ,then click [debugging port] to enabled port, but
echo =====if [debugging port] is enabled, Press any key 
pause
adb wait-for-device

:newFastbootRoot
adb wait-for-device
adb root
adb remount
if %errorlevel% equ 0 (echo ===%errorlevel%==root ok! Screenshot the current interface pls)
if %errorlevel% equ 1 (
    echo ==%errorlevel%===remount failed, reboot remount !!!!!
    adb wait-for-device
    adb reboot remount
    echo=
    echo =====Waitting for restart ......
    echo =====while Restart completed, waitting for port, phone *#558# ,then click [debugging port] to enabled port, but
    echo =====if [debugging port] is enabled, Press any key
    pause
    ping 127.0.0.1 -n 5 > nul
    adb wait-for-device
    adb wait-for-device
    adb root
    adb remount
    echo =====root ok! Screenshot the current interface pls
)
pause
pause
pause