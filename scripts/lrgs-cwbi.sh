#!/bin/bash

# Always rebuild
rm -rf ${LRGSHOME}/users
cp -r $DCSTOOL_HOME/lrgs/users .

rm -rf ${LRGSHOME}/netlist
cp -r $DCSTOOL_HOME/lrgs/netlist .

#  Always generate Generate Config
cat > $LRGSHOME/lrgs.conf <<EOF
archiveDir: ${LRGS_ARCHIVE}
numDayFiles: 31
ddsRecvConfig: ${LRGSHOME}/ddsrecv.conf
enableDdsRecv=true
enableDrgsRecv: false
drgsRecvConfig: ${LRGSHOME}/drgsconf.xml
htmlStatusSeconds: 10
ddsListenPort: 16003
ddsRequireAuth: true
# this prevents the LRGS from failing to respond if no data is available
noTimeout: true
$EXTRA_CONFIG
EOF

rm -f ${LRGSHOME}/.lrgs.passwd

index=0
echo "<ddsrecv>" > ${LRGSHOME}/ddsrecv.conf
# Setup users
if [ "$NOAACDA_USERNAME" != "" ]; then
    cat <<EOF | editPasswd
adduser $NOAACDA_USERNAME
$NOAACDA_PASSWORD
$NOAACDA_PASSWORD
addrole $NOAACDA_USERNAME dds
write
quit
EO    

    cat <<EOF >> ${LRGSHOME}/ddsrecv.conf
  <connection number="$index" host="cdadata.wcds.noaa.gov">
		<name>NOAA CDADTA</name>
		<port>16003</port>
		<enabled>true</enabled>
    <use-tls>TLS</use-tls>
		<username>${NOAACDA_USERNAME}</username>
		<authenticate>true</authenticate>
	</connection>
EOF
  index=$((index+1))
fi

script -c editPasswd <<EOF
adduser anonymous
anonymous
anonymous
anonymous
write
quit
EOF
cat <<EOF >> $LRGSHOME/ddsrecv.conf
<connection number="$index" host="lrgs.opendcs.org">
		<name>OpenDCS Public LRGS</name>
		<port>16003</port>
		<enabled>true</enabled>
		<username>anonymous</username>
		<authenticate>true</authenticate>
	</connection>
EOF
echo "</ddsrecv>" >> $LRGSHOME/ddsrecv.conf

script -c editPasswd <<EOF
adduser $ROUTING_USERNAME
$ROUTING_PASSWORD
$ROUTING_PASSWORD
addrole $ROUTING_USERNAME dds
write
quit
EOF


# Create user directories.
for user in `cat $LRGSHOME/.lrgs.passwd | cut -d : -f 1 -s`
do
    mkdir -p $LRGSHOME/users/$user
done

DH=$DCSTOOL_HOME

CP=$DH/bin/opendcs.jar

if [ -d "$LRGSHOME/dep" ]
then
  for f in $LRGSHOME/dep/*.jar
  do
    CP=$CP:$f
  done
fi

# Add the OpenDCS standard 3rd party jars to the classpath
for f in `ls $DH/dep/*.jar | sort`
do
   CP=$CP:$f
done

exec java -Xms120m $DECJ_MAXHEAP -cp $CP \
     -DDCSTOOL_HOME=$DH -DDECODES_INSTALL_DIR=$DH \
     -DDCSTOOL_USERDIR=$DCSTOOL_USERDIR -DLRGSHOME=$LRGSHOME \
     lrgs.lrgsmain.LrgsMain -d3 -l /dev/stdout -F -k -