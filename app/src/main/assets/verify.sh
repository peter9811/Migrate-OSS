#!sbin/sh

OUTFD="/dev/null"

for FD in `ls /proc/$$/fd`; do
	if readlink /proc/$$/fd/$FD | grep -q pipe; then
		if ps | grep -v grep | grep -q " 3 $FD "; then
			OUTFD=$FD
			break
		fi
	fi
done

echo "ui_print  " >> /proc/self/fd/$OUTFD;
echo "ui_print Verifying extras..." >> /proc/self/fd/$OUTFD;

mv /tmp/package-data /data/balti.migrate/package-data

res="$(cat /proc/cmdline | grep slot_suffix)";

if [ ! -e /system/app/MigrateHelper/MigrateHelper.apk ]; then
    echo "ui_print  " >> /proc/self/fd/$OUTFD;
    echo "ui_print **********************************" >> /proc/self/fd/$OUTFD;
    echo "ui_print Helper not installed successfully!" >> /proc/self/fd/$OUTFD;
    echo "ui_print  Please report to the developer!! " >> /proc/self/fd/$OUTFD;
    echo "ui_print  " >> /proc/self/fd/$OUTFD;
    echo "ui_print Deleting migrate cache..." >> /proc/self/fd/$OUTFD;
    echo "ui_print **********************************" >> /proc/self/fd/$OUTFD;
    echo "ui_print  " >> /proc/self/fd/$OUTFD;
    rm -rf /system/app/MigrateHelper
    rm -rf /data/balti.migrate
    unsuccessful_unpack=true
    sleep 2s
else
    unsuccessful_unpack=false
fi

if [ -e /tmp/extras-data ] && [ "$unsuccessful_unpack" = false ]
then
	ed=/tmp/extras-data
	while read -r line || [[ -n "$line" ]]; do
        if [ ! -e /data/balti.migrate/${line} ]; then
            echo "ui_print $line was not unpacked" >> /proc/self/fd/$OUTFD;
        fi
    done < "$ed"
fi

if [ -n "$res" ]; then
    umount /system
fi

if [ "$unsuccessful_unpack" = true ]; then
    umount /data
fi