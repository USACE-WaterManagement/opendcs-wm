#!/bin/bash

if [ ! -d $LRGSHOME/netlist ]; then
    echo "Generating initial LRGS HOME Directory."
    cp -r $DCSTOOL_HOME/users .
    cp -r $DCSTOOL_HOME/netlist .

# Generate Config
cat > $LRGSHOME/lrgs.conf <<EOF
archiveDir: "$LRGS_ARCHIVE"
numDayFiles: 31
ddsRecvConfig: "${LRGSHOME}/ddsrecv.conf"
enableDrgsRecv: false
drgsRecvConfig: "${LRGSHOME}/drgsconf.xml"
htmlStatusSeconds: 10
ddsListenPort: 16003
ddsRequireAuth: true
# this prevents the LRGS from failing to respond if no data is available
noTimeout: true
$EXTRA_CONFIG
EOF
fi

# Setup users
if [ "$NOAACDA_USERNAME" != "" ]; then
    cat <<EOF | editPasswd
adduser $NOAACDA_USERNAME
$NOAACDA_PASSWORD
$NOAACDA_PASSWORD
addrole $NOAACDA_USERNAME dds
write
quit
EOF
    # TODO: add ddsrecv.xml elements
    echo "enableDdsRecv=true" >> $LRGSHOME/lrgs.conf
fi

cat <<EOF | editPasswd
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