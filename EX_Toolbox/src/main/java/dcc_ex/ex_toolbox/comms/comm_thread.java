/*Copyright (C) 2018 M. Steve Todd
  mstevetodd@gmail.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package dcc_ex.ex_toolbox.comms;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import dcc_ex.ex_toolbox.type.Consist;
import dcc_ex.ex_toolbox.R;
import dcc_ex.ex_toolbox.type.message_type;
import dcc_ex.ex_toolbox.threaded_application;

public class comm_thread extends Thread {
    JmDNS jmdns = null;
    volatile boolean endingJmdns = false;
    withrottle_listener listener;
    android.net.wifi.WifiManager.MulticastLock multicast_lock;
    static socketWifi socketWiT;
    static heartbeat heart = new heartbeat();
    private static long lastSentMs = System.currentTimeMillis();
    private static long lastQueuedMs = System.currentTimeMillis();

    public static myTimer currentTimer = new myTimer();

    protected static threaded_application mainapp;  // hold pointer to mainapp
    protected static SharedPreferences prefs;

    protected String LATCHING_DEFAULT;

    private static int requestLocoIdForWhichThrottleDccex;

    static final String[] TRACK_TYPES = {"NONE", "MAIN", "PROG", "DC", "DCX", "AUTO", "EXT", "PROG"};
    static final boolean[] TRACK_TYPES_NEED_ID = {false, false, false, true, true, false, false, false};
//    static final boolean[] TRACK_TYPES_SELECTABLE = {true, true, true, true, true, true, false, false};

    public comm_thread(threaded_application myApp, SharedPreferences myPrefs) {
        super("comm_thread");

        mainapp = myApp;
        prefs = myPrefs;

        this.start();
    }

    /* ******************************************************************************************** */

    //Listen for a WiThrottle service advertisement on the LAN.
    public class withrottle_listener implements ServiceListener {

        public void serviceAdded(ServiceEvent event) {
            //          Log.d("EX_Toolbox", String.format("comm_thread.serviceAdded fired"));
            //A service has been added. If no details, ask for them
            Log.d("EX_Toolbox", String.format("comm_thread.serviceAdded for '%s', Type='%s'", event.getName(), event.getType()));
            ServiceInfo si = jmdns.getServiceInfo(event.getType(), event.getName(), 0);
            if (si == null || si.getPort() == 0) {
                Log.d("EX_Toolbox", String.format("comm_thread.serviceAdded, requesting details: '%s', Type='%s'", event.getName(), event.getType()));
                jmdns.requestServiceInfo(event.getType(), event.getName(), true, 1000);
            }
        }

        public void serviceRemoved(ServiceEvent event) {
            //Tell the UI thread to remove from the list of services available.
            mainapp.sendMsg(mainapp.connection_msg_handler, message_type.SERVICE_REMOVED, event.getName()); //send the service name to be removed
            Log.d("EX_Toolbox", String.format("comm_thread.serviceRemoved: '%s'", event.getName()));
        }

        public void serviceResolved(ServiceEvent event) {
            //          Log.d("EX_Toolbox", String.format("comm_thread.serviceResolved fired"));
            //A service's information has been resolved. Send the port and service name to connect to that service.
            int port = event.getInfo().getPort();

            String serverType = event.getInfo().getPropertyString("jmri") == null ? "" : "JMRI";
            if (event.getType().startsWith("_dccex")) {
                serverType = "DCCEX";
            }

            String host_name = event.getInfo().getName();
            if (event.getInfo().getType().toString().equals(mainapp.JMDNS_SERVICE_JMRI_DCCPP_OVERTCP)) {
                host_name = host_name + " [DCC-EX]";
            }
            Inet4Address[] ip_addresses = event.getInfo().getInet4Addresses();  //only get ipV4 address
            String ip_address = ip_addresses[0].toString().substring(1);  //use first one, since WiThrottle is only putting one in (for now), and remove leading slash

            //Tell the UI thread to update the list of services available.
            HashMap<String, String> hm = new HashMap<>();
            hm.put("ip_address", ip_address);
            hm.put("port", ((Integer) port).toString());
            hm.put("host_name", host_name);
            hm.put("ssid", mainapp.client_ssid);
            hm.put("service_type", event.getInfo().getType().toString());

            String key = ""+ip_address+":"+port;
            mainapp.knownDccexServerIps.put(key, serverType);

            Message service_message = Message.obtain();
            service_message.what = message_type.SERVICE_RESOLVED;
            service_message.arg1 = port;
            service_message.obj = hm;  //send the hashmap as the payload
            boolean sent = false;
            try {
                sent = mainapp.connection_msg_handler.sendMessage(service_message);
            } catch (Exception ignored) {
            }
            if (!sent)
                service_message.recycle();

            Log.d("EX_Toolbox", String.format("comm_thread.serviceResolved - %s(%s):%d -- %s",
                    host_name, ip_address, port, event.toString().replace(System.getProperty("line.separator"), " ")));

        }
    }

    void startJmdns() {
        //Set up to find a WiThrottle service via ZeroConf
        try {
            if (mainapp.client_address != null) {
                WifiManager wifi = (WifiManager) mainapp.getSystemService(Context.WIFI_SERVICE);

                if (multicast_lock == null) {  //do this only as needed
                    multicast_lock = wifi.createMulticastLock("ex_toolbox");
                    multicast_lock.setReferenceCounted(true);
                }

                Log.d("EX_Toolbox", "comm_thread.startJmdns: local IP addr " + mainapp.client_address);

                jmdns = JmDNS.create(mainapp.client_address_inet4, mainapp.client_address);  //pass ip as name to avoid hostname lookup attempt

                listener = new withrottle_listener();
                Log.d("EX_Toolbox", "comm_thread.startJmdns: listener created");

            } else {
                threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppNoLocalIp), Toast.LENGTH_LONG);
            }
        } catch (Exception except) {
            Log.e("EX_Toolbox", "comm_thread.startJmdns - Error creating withrottle listener: " + except.getMessage());
            threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppErrorCreatingWiThrottle, except.getMessage()), Toast.LENGTH_SHORT);
        }
    }

    //endJmdns() takes a long time, so put it in its own thread
    void endJmdns() {
        Thread jmdnsThread = new Thread("EndJmdns") {
            @Override
            public void run() {
                try {
                    Log.d("EX_Toolbox", "comm_thread.endJmdns: removing jmdns listener");
                    jmdns.removeServiceListener(mainapp.JMDNS_SERVICE_WITHROTTLE, listener);
                    jmdns.removeServiceListener(mainapp.JMDNS_SERVICE_JMRI_DCCPP_OVERTCP, listener);

                    multicast_lock.release();
                } catch (Exception e) {
                    Log.d("EX_Toolbox", "comm_thread.endJmdns: exception in jmdns.removeServiceListener()");
                }
                try {
                    Log.d("EX_Toolbox", "comm_thread.endJmdns: calling jmdns.close()");
                    jmdns.close();
                    Log.d("EX_Toolbox", "comm_thread.endJmdns: after jmdns.close()");
                } catch (Exception e) {
                    Log.d("EX_Toolbox", "comm_thread.endJmdns: exception in jmdns.close()");
                }
                jmdns = null;
                endingJmdns = false;
                Log.d("EX_Toolbox", "comm_thread.endJmdns run exit");
            }
        };
        if (jmdnsIsActive()) {      //only need to run one instance of this thread to terminate jmdns
            endingJmdns = true;
            jmdnsThread.start();
            Log.d("EX_Toolbox", "comm_thread.endJmdns active so ending it and starting thread to remove listener");
        } else {
//            jmdnsThread = null;
            Log.d("EX_Toolbox", "comm_thread.endJmdns not active");
        }
    }

    boolean jmdnsIsActive() {
        return jmdns != null && !endingJmdns;
    }

    /*
      add configuration of digitrax LnWi or DCCEX to discovered list, since they do not provide mDNS
     */
    void addFakeDiscoveredServer(String entryName, String clientAddr, String entryPort, String serverType) {

        if (clientAddr == null || clientAddr.lastIndexOf(".") < 0)
            return; //bail on unexpected value

        //assume that the server is at x.y.z.1
        String server_addr = clientAddr.substring(0, clientAddr.lastIndexOf("."));
        server_addr += ".1";
        HashMap<String, String> hm = new HashMap<>();
        hm.put("ip_address", server_addr);
        hm.put("port", entryPort);
        hm.put("host_name", entryName);
        hm.put("ssid", mainapp.client_ssid);
        hm.put("service_type",(serverType.equals("DCC-EX") ? mainapp.JMDNS_SERVICE_JMRI_DCCPP_OVERTCP : mainapp.JMDNS_SERVICE_WITHROTTLE) );

//        mainapp.knownDCCEXserverIps.put(server_addr, serverType);
        String key = ""+server_addr+":"+entryPort;
        mainapp.knownDccexServerIps.put(key, serverType);

        Message service_message = Message.obtain();
        service_message.what = message_type.SERVICE_RESOLVED;
        service_message.arg1 = mainapp.port;
        service_message.obj = hm;  //send the hashmap as the payload
        boolean sent = false;
        try {
            sent = mainapp.connection_msg_handler.sendMessage(service_message);
        } catch (Exception ignored) {
        }
        if (!sent)
            service_message.recycle();

        Log.d("EX_Toolbox", String.format("comm_thread.addFakeDiscoveredServer: added '%s' at %s to Discovered List", entryName, server_addr));

    }

//        class comm_handler extends Handler {}

    protected void stoppingConnection() {
        heart.stopHeartbeat();
        endJmdns();
//            dlMetadataTask.stop();
//        threaded_application.dlRosterTask.stop();
    }

    protected void shutdown(boolean fast) {
        Log.d("EX_Toolbox", "comm_thread.Shutdown");
        if (socketWiT != null) {
            socketWiT.disconnect(true, fast);     //stop reading from the socket
        }
        Log.d("EX_Toolbox", "comm_thread.Shutdown: socketWit down");
        mainapp.host_ip = null;
        mainapp.port = 0;
        threaded_application.reinitStatics();                    // ensure activities are ready for relaunch
        mainapp.doFinish = false;                   //ok for activities to run if restarted after this

//        threaded_application.dlRosterTask.stop();
//            dlMetadataTask.stop();

        Log.d("EX_Toolbox", "comm_thread.Shutdown finished");
    }

    /* ******************************************************************************************** */
    /* ******************************************************************************************** */

    protected void sendThrottleName() {
        sendThrottleName(true);
    }

    private static void sendThrottleName(Boolean sendHWID) {
        //DCC-EX // name is not relevant, so send a Command Station Status Request
//        Log.d("EX_Toolbox", "comm_thread.sendThrottleName DCC-EX: <s>");
        if (mainapp.dccexListsRequested < 0) { // if we haven't received all the lists go ask for them
            wifiSend("<s>");
            sendRequestRoster();
            sendRequestTurnouts();
            sendRequestRoutes();
            sendRequestTracks();
            sendAllSensorDetailsRequest();
            mainapp.dccexListsRequested = 0;  // don't ask again
        } else {
            wifiSend("<#>");
        }
    }

    /* ask for specific loco to be added to a throttle
         input addr is formatted "L37<;>CSX37" or "S96" (if no roster name)
         msgTxt will be formatted M0+L1012<;>EACL1012 or M1+S96<;>S96 */
    static void sendAcquireLoco(String addr, int whichThrottle, int interval) {
//        String rosterName;
        String address;
        String[] as = threaded_application.splitByString(addr, "<;>");
        if (as.length > 1) {
            address = as[0];
        } else { //if no rostername, just use address for both
            address = addr;
        }

        String msgTxt;
         //DCC-EX
        if (!address.equals("*")) {
            msgTxt = String.format("<t %s>", address.substring(1));  //add requested loco to this throttle

            Consist con = mainapp.consists[whichThrottle];
            con.setConfirmed(address); // don't wait for confirmation
            con.setWhichSource(address, 1); //entered by address, not roster
            wifiSend(msgTxt);
            mainapp.sendMsg(mainapp.comm_msg_handler, message_type.WIFI_SEND, msgTxt);

            String lead = mainapp.consists[whichThrottle].getLeadAddr();
            if (lead.equals(address)) {
                sendRequestRosterLocoDetails(address); // get the CS to resend the Loco details so we can get the functions
            }

            if (heart.getInboundInterval() > 0 && !heart.isHeartbeatSent()) {
                mainapp.sendMsg(mainapp.comm_msg_handler, message_type.SEND_HEARTBEAT_START);
            }
            mainapp.sendMsgDelay(mainapp.comm_msg_handler, 1000L, message_type.REFRESH_FUNCTIONS);
        } else { // requesting the loco id on the programming track.  Using the DCC-EX driveway feature
            requestLocoIdForWhichThrottleDccex = whichThrottle;
            wifiSend("<R>");
        }

//            Log.d("EX_Toolbox", "comm_thread.sendAcquireLoco DCC-EX: " + msgTxt);
    }

    protected static void sendRequestRosterLocoDetails(String addr) {
        wifiSend("<JR " + addr.substring(1) + ">");
    }


    //release all locos for throttle using '*', or a single loco using address
    protected void sendReleaseLoco(String addr, int whichThrottle, long interval) {
        // not relevant to DCC-EX
        String msgTxt = "";
    }

    protected void sendEStop(int whichThrottle) {
        //DCC-EX
        wifiSend("<!>");
//        Log.d("EX_Toolbox", "comm_thread.sendEStop DCC-EX: ");
    }

    protected void sendDisconnect() {
        //  DCC-EX   no equivalent to a "Q" so just drop all the locos to be tidy
        Consist con;
        if (mainapp.consists != null) {
            if (mainapp.consists.length > 0) {
                for (int i = 0; i < mainapp.consists.length; i++) {
                    con = mainapp.consists[i];
                    for (Consist.ConLoco l : con.getLocos()) {
                        sendReleaseLoco(l.getAddress(), i, 0);
                    }
                }
            }
        }
        wifiSend("<U DISCONNECT>");  // this is not a real command.  just a placeholder that will be ignored by the CS
        shutdown(true);
    }

    protected void sendFunction(char cWhichThrottle, String addr, int fn, int fState) {
        sendFunction(mainapp.throttleCharToInt(cWhichThrottle), addr, fn, fState);
    }

    @SuppressLint("DefaultLocale")
    protected void sendFunction(int whichThrottle, String addr, int fn, int fState) {
        //DCC-EX
        String msgTxt;

        String isLatching = mainapp.function_consist_latching.get(fn);
        int newfState = -1;

        if (mainapp.throttleFunctionIsLatchingDccex[whichThrottle] != null) {  //  we have a roster specific latching for this
            if (fn < mainapp.throttleFunctionIsLatchingDccex[whichThrottle].length) {
                isLatching = mainapp.throttleFunctionIsLatchingDccex[whichThrottle][fn] ? LATCHING_DEFAULT : "none";
            }
        } else {   // no roster entry
            if (fn>2) { // assume it is latching
                isLatching = LATCHING_DEFAULT;
            }
        }

        if ((isLatching != null) && (isLatching.equals(LATCHING_DEFAULT))) {
            if (mainapp.function_states[whichThrottle][fn]) { // currently pressed
                if (fState == 1) newfState = 0;
            } else { // not currently pressed
                if (fState == 1) newfState = 1;
            }
        } else {
            newfState = fState;
        }

        if (newfState >= 0) {
            if ((addr.length() == 0) || (addr.equals("*"))) { // all on the throttle
                Consist con = mainapp.consists[whichThrottle];
                for (Consist.ConLoco l : con.getLocos()) {
                    msgTxt = String.format("<F %s %d %d>", l.getAddress().substring(1, l.getAddress().length()), fn, newfState);
                    wifiSend(msgTxt);
//                        Log.d("EX_Toolbox", "comm_thread.sendSpeed DCC-EX: " + msgTxt);
                }
            } else { // just one address
                msgTxt = String.format("<F %s %d %d>", addr.substring(1, addr.length()), fn, newfState);
                wifiSend(msgTxt);
//                    Log.d("EX_Toolbox", "comm_thread.sendFunction DCC-EX: " + msgTxt);
            }
        }
    }

    protected void sendForceFunction(char cWhichThrottle, String addr, int fn, int fState) {
        sendForceFunction(mainapp.throttleCharToInt(cWhichThrottle), addr, fn, fState);
    }

    @SuppressLint("DefaultLocale")
    protected void sendForceFunction(int whichThrottle, String addr, int fn, int fState) {
        sendFunction(whichThrottle, addr, fn, fState);
    }

    protected static void sendRequestRoster() {
        String msgTxt = "<JR>";
        wifiSend(msgTxt);
//         Log.d("EX_Toolbox", "comm_thread.sendRequestRoster DCC-EX: " + msgTxt);
    }

    protected static void sendRequestTurnouts() {
        String msgTxt = "<JT>";
        wifiSend(msgTxt);
//         Log.d("EX_Toolbox", "comm_thread.sendRequestTurnouts DCC-EX: " + msgTxt);
    }

    protected static void sendRequestRoutes() {
        String msgTxt = "<JA>";
        wifiSend(msgTxt);
//         Log.d("EX_Toolbox", "comm_thread.sendRequestRoutes DCC-EX: " + msgTxt);
    }

    protected static void sendRequestTracks() {
        if (mainapp.DccexVersionValue >= threaded_application.DCCEX_MIN_VERSION_FOR_TRACK_MANAGER) {  /// need to remove the track manager option
            String msgTxt = "<=>";
            wifiSend(msgTxt);
//            Log.d("EX_Toolbox", "comm_thread.sendRequestTracks DCC-EX: " + msgTxt);
        }
    }

    protected static void sendTrackPower(String track, int powerState) {
        if (mainapp.isDccex) { // DCC-EX only
            String msgTxt = "<" + ((char) ('0' + powerState)) + " " + track + ">";
            wifiSend(msgTxt);
        }
    }

    protected static void sendTrack(String track, String type, int id) {
        String msgTxt = "";
        boolean needsId = false;
        for (int i=0; i<TRACK_TYPES.length; i++) {
            if (type.equals(TRACK_TYPES[i])) {
                needsId = TRACK_TYPES_NEED_ID[i];
                break;
            }
        }
        if (!needsId) {
            msgTxt = msgTxt + "<= " + track + " "+ type + ">";
            wifiSend(msgTxt);
        } else {
            msgTxt = msgTxt + "<= " + track + " "+ type + " " + id + ">";
            wifiSend(msgTxt);
        }
//         Log.d("EX_Toolbox", "comm_thread.sendTracks DCC-EX: " + msgTxt);
    }

    protected static void joinTracks() {
        joinTracks(true);
    }
    protected static void joinTracks(boolean join) {
        if (join) {
            wifiSend("<1 JOIN>");
        } else {
            wifiSend("<0 PROG>");
            wifiSend("<1 PROG>");
        }
    }


    static void sendAllSensorDetailsRequest(){
        String msgTxt;
        msgTxt = "<S>";
        wifiSend(msgTxt);
        Log.d("EX_Toolbox", "comm_thread.sendAllSensorDetailsRequest DCC-EX: " + msgTxt);
    }

    static void sendSensorRequest(String id, String vpin, String pullup){
        String msgTxt;
        msgTxt = "<S " + id + " " + vpin + " " + pullup +" >";
        wifiSend(msgTxt);
        Log.d("EX_Toolbox", "comm_thread.sendSensorRequest DCC-EX: " + msgTxt);
    }

    static void sendAllSensorsRequest(){
        String msgTxt;
        msgTxt = "<Q>";
        wifiSend(msgTxt);
        Log.d("EX_Toolbox", "comm_thread.sendSensorRequest DCC-EX: " + msgTxt);
    }

    static void sendMoveServo(String cmd) {
        String msgTxt;
        msgTxt = "<D SERVO " + cmd + ">";
        wifiSend(msgTxt);
        Log.d("EX_Toolbox", "comm_thread.sendMoveServo DCC-EX: " + msgTxt);
    }

    static void sendServoDetailsRequest(String cmd) {
        String msgTxt;
        msgTxt = "<T " + cmd + " X>";
        wifiSend(msgTxt);
        Log.d("EX_Toolbox", "comm_thread.sendServoDetailsRequest DCC-EX: " + msgTxt);
    }

    protected static void sendRequestCurrents() {
        String msgTxt = "<JI>";
        wifiSend(msgTxt);
    }

    protected static void sendRequestCurrentsMax() {
        String msgTxt = "<JG>";
        wifiSend(msgTxt);
    }

    protected void sendTurnout(String cmd) {
        //DCC-EX
        String systemName = cmd.substring(1);
        String translatedState = "T";
        switch (cmd.charAt(0)) {
            case 'C':
                translatedState = "C";
            case '2': { // toggle
                int pos = findTurnoutPos(systemName);
                if (pos >= 0) {
                    if (mainapp.to_states[pos].equals("4")) {
                        translatedState = "C";
                    }
                }
            }
        }
        String msgTxt = "<T " + cmd.substring(1) + " " + translatedState + ">";              // format <T id 0|1|T|C>
        wifiSend(msgTxt);
//            Log.d("EX_Toolbox", "comm_thread.sendTurnout DCC-EX: " + msgTxt);
    }

    protected void sendRoute(String cmd) {
        //DCC-EX    Route: </START id>      Automation: </START id addr>
        String systemName = cmd.substring(1);
        String msgTxt = "</START";
        try {
            String whichLoco;
            int type = -1;
//                whichLoco = mainapp.getConsist(mainapp.whichThrottleLastTouch).getLeadAddr();
            whichLoco = mainapp.getConsist(0).getLeadAddr();
            if (whichLoco.length()>0) {
                String routeType = "";
                int routeId = Integer.parseInt(systemName);
                for (int i = 0; i < mainapp.routeIDsDccex.length; i++) {
                    if (mainapp.routeIDsDccex[i]==routeId) {
                        routeType = mainapp.routeTypesDccex[i];
                        break;
                    }
                }
                if (routeType.equals("A")) // automation
                   msgTxt = msgTxt + " " + whichLoco.substring(1);
            }
        } catch (Exception ignored) {
        }
        msgTxt = msgTxt + " " + systemName + ">";
        wifiSend(msgTxt);
//            Log.d("EX_Toolbox", "comm_thread.sendRoute DCC-EX: " + msgTxt);
    }

    @SuppressLint("DefaultLocale")
    protected void sendPower(int pState) {
        //DCC-EX
        String msgTxt = String.format("<%d>", pState);
        wifiSend(msgTxt);
//            Log.d("EX_Toolbox", "comm_thread.sendPower DCC-EX: " + msgTxt);
    }

    @SuppressLint("DefaultLocale")
    protected void sendPower(int pState, int track) {  // DCC-EX only
        char trackLetter = (char) ('A' + track);
        if (mainapp.isDccex) { //DCC-EX
            String msgTxt = String.format("<%d %s>", pState, trackLetter);
            wifiSend(msgTxt);
//            Log.d(""EX_Toolbox", "comm_thread.sendPower DCC-EX: " + msgTxt);
        }
    }

    @SuppressLint("DefaultLocale")
    protected static void sendJoinDccex() {
        // DCC-EX
        wifiSend("<1 JOIN>");
    }

    protected void sendQuit() {
        // N/A for DCC-EX
    }

    protected void sendHeartbeatStart() {
        //DCC-EX
        heart.setHeartbeatSent(true);
        wifiSend("<#>"); // DCC-EX doesn't have heartbeat, so sending a command with a simple response
//            Log.d("EX_Toolbox", "comm_thread.sendHeartbeatStart DCC-EX: <#>)");
    }

    @SuppressLint("DefaultLocale")
    protected void sendDirection(int whichThrottle, String addr, int dir) {
        //DCC-EX
        String msgTxt;
        if ((addr.length() == 0) || (addr.equals("*"))) { // all on the throttle
            Consist con = mainapp.consists[whichThrottle];
            for (Consist.ConLoco l : con.getLocos()) {
                int newDir = dir;
                if (l.isBackward()) newDir = (dir == 0) ? 1 : 0;
                msgTxt = String.format("<t 0 %s %d %d>", l.getAddress().substring(1), mainapp.lastKnownSpeedDCCEX[whichThrottle], newDir);
                wifiSend(msgTxt);
                mainapp.lastKnownDirDccex[whichThrottle] = newDir;
//                    Log.d("EX_Toolbox", "comm_thread.sendSpeed DCC-EX: " + msgTxt);
            }
        } else {
            msgTxt = String.format("<t 0 %s %d %d>", addr.substring(1), mainapp.lastKnownSpeedDCCEX[whichThrottle], dir);
            wifiSend(msgTxt);
            mainapp.lastKnownDirDccex[whichThrottle] = dir;
//                Log.d("EX_Toolbox", "comm_thread.sendDirection DCC-EX: " + msgTxt);
        }
    }

    protected static void sendSpeedZero(int whichThrottle) {
        sendSpeed(whichThrottle, 0);
    }

    @SuppressLint("DefaultLocale")
    protected static void sendSpeed(int whichThrottle, int speed) {
        //DCC-EX
        Consist con = mainapp.consists[whichThrottle];
        String msgTxt;
        for (Consist.ConLoco l : con.getLocos()) {
            int dir = mainapp.lastKnownDirDccex[whichThrottle];
            int newDir = dir;
            if (l.isBackward()) newDir = (dir == 0) ? 1 : 0;
            msgTxt = String.format("<t 0 %s %d %d>", l.getAddress().substring(1), speed, newDir);
            wifiSend(msgTxt);
//                Log.d("EX_Toolbox", "comm_thread.sendSpeed DCC-EX: " + msgTxt);
        }
    }

    @SuppressLint("DefaultLocale")
    protected static void setSpeedDirect(int locoAddr, int speed, int dir) {
        //DCC-EX
        String msgTxt = String.format("<t 0 %d %d %d>", locoAddr, speed, dir);
        wifiSend(msgTxt);
//                Log.d("EX_Toolbox", "comm_thread.setSpeedDirect DCC-EX: " + msgTxt);
    }

    @SuppressLint("DefaultLocale")
    protected static void sendRequestSpeedAndDir(int whichThrottle) {
        //DCC-EX
        Consist con = mainapp.consists[whichThrottle];
        String msgTxt;
        for (Consist.ConLoco l : con.getLocos()) {
            msgTxt = String.format("<t %s>", l.getAddress().substring(1));
            wifiSend(msgTxt);
//                Log.d("EX_Toolbox", "comm_thread.sendRequestSpeedAndDir DCC-EX: " + msgTxt);
        }
    }

    @SuppressLint("DefaultLocale")
    public static void sendWriteDecoderAddress(int addr) {
        // DCC-EX only
        String msgTxt = String.format("<W %s>", addr);
        wifiSend(msgTxt);
    }

    @SuppressLint("DefaultLocale")
    public static void sendReadCv(int cv) {
        // DCC-EX only
        String msgTxt = String.format("<R %d>", cv);
        wifiSend(msgTxt);
    }

    @SuppressLint("DefaultLocale")
    public static void sendWriteCv(int cvValue, int cv) {
        // DCC-EX only
        String msgTxt = String.format("<W %d %d>", cv, cvValue);
        wifiSend(msgTxt);
    }

    @SuppressLint("DefaultLocale")
    public static void sendNeopixel(String vpin, String red, String green, String blue, String count) {
        String msgTxt = "";
        if (count.isEmpty()) {
            msgTxt = String.format("<o %s %s %s %s>", vpin, red, green, blue);
        } else {
            msgTxt = String.format("<o %s %s %s %s %s>", vpin, red, green, blue, count);
        }
        wifiSend(msgTxt);
    }

    @SuppressLint("DefaultLocale")
    public static void sendNeopixelOnOff(String vpin, String count) {
        String msgTxt = "";
        if (count.isEmpty()) {
            msgTxt = String.format("<o %s>", vpin);
        } else {
            msgTxt = String.format("<o %s %s>", vpin, count);
        }
        wifiSend(msgTxt);
    }

    @SuppressLint("DefaultLocale")
    public static void sendWritePomCv(int cv, int cvValue, int addr) {
        // DCC-EX only
        String msgTxt = String.format("<w %d %d %d>", addr, cv, cvValue);
        wifiSend(msgTxt);
    }

    @SuppressLint("DefaultLocale")
    public static void sendDccexCommand(String msgTxt) {
        // DCC-EX only
        wifiSend(msgTxt);
    }


    /* ******************************************************************************************** */
    /* ******************************************************************************************** */

    @SuppressLint("DefaultLocale")
    protected static void processWifiResponse(String responseStr) {
            /* see java/arc/jmri/jmrit/withrottle/deviceserver.java for server code and some documentation
          VN<Version#>
          RL<RosterSize>]<RosterList>
          RF<RosterFunctionList>
          RS<2ndRosterFunctionList>
          *<HeartbeatIntervalInSeconds>
          PTL[<SystemTurnoutName><UserName><State>repeat] where state 1=Unknown. 2=Closed, 4=Thrown
          PTA<NewTurnoutState><SystemName>
          PPA<NewPowerState> where state 0=off, 1=on, 2=unknown
          M<throttleid> multi-throttle command
          TODO: add remaining items, or move examples into code below
             */

        //send response to debug log for review
        Log.d("EX_Toolbox", "comm_thread.processWifiResponse: " + (mainapp.isDccex ? "DCC-EX" : "") + "<--:" + responseStr);

        boolean skipAlert = false;          //set to true if the Activities do not need to be Alerted

        // DCC-EX
        if (responseStr.length() >= 3) {
            if (!(responseStr.charAt(0) == '<')) {
                if (responseStr.contains("<")) { // see if we can clean it up
                    responseStr = responseStr.substring(responseStr.indexOf("<"));
                }
            }

            if (responseStr.charAt(0) == '<') {
                if ((mainapp.dccexScreenIsOpen) && (responseStr.charAt(1)!='#') ) {
                    displayCommands(responseStr, true);
                    mainapp.alert_activities(message_type.DCCEX_RESPONSE, responseStr);
                }


                String[] args = responseStr.substring(1, responseStr.length() - 1).split(" (?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 999);

                switch (responseStr.charAt(1)) {
                    case 'i': // Command Station Information or current information
                        String old_vn = mainapp.DccexVersion;
                        String[] vn1 = args[1].split("-");
                        String[] vn2 = vn1[1].split("\\.");
                        String vn = "4.";
                        try {
                            vn = String.format("%02d.", Integer.parseInt(vn2[0]));
                        } catch (Exception e) {
                            Log.d("EX_Toolbox", "comm_thread.processWifiResponse: Invalid Version " + mainapp.DccexVersion + ", ignoring");
                        }
                        if (vn2.length>=2) {
                            try { vn = vn +String.format("%02d",Integer.parseInt(vn2[1]));
                            } catch (Exception ignored) {
                                // try to pull a partial number
                                String pn = "0";
                                for (int j=0; j<vn2[1].length(); j++ ) {
                                    if ( (vn2[1].charAt(j)>='0') && (vn2[1].charAt(j)<='9') ) {
                                        pn = pn + vn2[1].charAt(j);
                                    } else { break; }
                                }
                                vn = vn +String.format("%03d", Integer.parseInt(pn));
                            }
                        }
                        if (vn2.length>=3) {
                            try { vn = vn +String.format("%03d",Integer.parseInt(vn2[2]));
                            } catch (Exception ignored) {
                                // try to pull a partial number
                                String pn = "0";
                                for (int j=0; j<vn2[2].length(); j++ ) {
                                    if ( (vn2[2].charAt(j)>='0') && (vn2[2].charAt(j)<='9') ) {
                                        pn = pn + vn2[2].charAt(j);
                                    } else { break; }
                                }
                                vn = vn +String.format("%03d", Integer.parseInt(pn));
                            }
                        }

                        mainapp.DccexVersion = vn;
                        try {
                            mainapp.DccexVersionValue = Double.valueOf(mainapp.DccexVersion);
                        } catch (Exception ignored) { } // invalid version

                        if (!mainapp.DccexVersion.equals(old_vn)) { //only if changed
                            mainapp.sendMsg(mainapp.connection_msg_handler, message_type.CONNECTED);
                        } else {
                            Log.d("EX_Toolbox", "comm_thread.processWifiResponse: version already set to " + mainapp.DccexVersion + ", ignoring");
                        }

//                            mainapp.withrottle_version = 4.0;  // fudge it
                        mainapp.setServerType("DCC-EX");
                        mainapp.setServerDescription(responseStr.substring(2, responseStr.length() - 1)); //store the description

                        skipAlert = true;
                        mainapp.heartbeatInterval = 20000; // force a heartbeat period
                        heart.startHeartbeat(mainapp.heartbeatInterval);
                        mainapp.power_state = "2"; // unknown

                        break;

                    case 'l':
                        processDccexLocos(args);
                        skipAlert = true;
                        break;

                    case 'r':
                        if (args.length<=2) { // response from a request for a loco id (the Drive away feature, and also the Address read)
                            processDccexRequestLocoIdResponse(args);
                        } else { // response from a CV write
                            processDccexRequestCvResponse(args);
                        }
                        skipAlert = true;
                        break;

                    case 'p': // power response
                        processDccexPowerResponse(args);
                        skipAlert = true;
                        break;

                    case 'j': //roster, turnouts / routes lists
                        skipAlert = true;
                        switch (responseStr.charAt(2)) {
                            case 'T': // turnouts
                                processDccexTurnouts(args);
                                break;
                            case 'A': // automations/routes
                                processDccexRoutes(args);
                                break;
                            case 'R': // roster
                                skipAlert = processDccexRoster(args);
                                break;
                            case 'C': // fastclock
                                processDccexFastClock(args);
                                break;
                            case 'I':
                                processDccexCurrents(args);
                                break;
                            case 'G':
                                processDccexCurrentsMax(args);
                                break;                        }
                        break;

                    case 'H': //Turnout change
                        if (args.length==2) {
                            responseStr = "PTA" + (((args[2].equals("T")) || (args[2].equals("1"))) ? 4 : 2) + args[1];
                            processTurnoutChange(responseStr);
                        } else if (args.length==8) {
                            processTurnoutDetails(args);
                        }
                        break;

                    case 'v': // response from a request a CV value
                        processDccexRequestCvResponse(args);
                        skipAlert = true;
                        break;

                    case 'w': // response from an address write or other CV write
                        responseStr = args[1];
                        if (!args[1].equals("-1")) {
                            mainapp.alert_activities(message_type.WRITE_DECODER_SUCCESS, responseStr);
                        } else {
                            mainapp.alert_activities(message_type.WRITE_DECODER_FAIL, responseStr);
                        }
                        break;

                    case '=': // Track Manager response
                        processDccexTrackManagerResponse(args);
                        skipAlert = true;
                        break;

                    case 'm': //info message sent from server to throttle
                        threaded_application.safeToast(args[1], Toast.LENGTH_LONG); // copy to UI as toast message
                        break;

                    case 'Q': // sensor active
                        if (args.length == 2) {
                            processDccexSensorResponse(args, true);
                        } else {
                            processDccexSensorDetailsResponse(args);
                        }
                        skipAlert = true;
                        break;

                    case 'q': // sensor inactive
                        processDccexSensorResponse(args, false);
                        skipAlert = true;
                        break;

                }

            } else { // ignore responses that don't start with "<"
                skipAlert = true;
            }
        } else {
            skipAlert = true;
        }

        if (!skipAlert) {
            mainapp.alert_activities(message_type.RESPONSE, responseStr);  //send response to running activities
        }
    }  //end of processWifiResponse

    /* ***********************************  *********************************** */

    private static  void processDccexPowerResponse ( String [] args) { // <p0|1 [A|B|C|D|E|F|G|H|MAIN|PROG|DC|DCX]>
        String oldState = mainapp.power_state;
        String responseStr;
        if ( (args.length==1)   // <p0|1>
                || ((args.length==2) && (args[0].length()==1) && (args[1].charAt(0)<='2')) ) {  // <p 0|1>
            char power;
            if ( (args[0].length() == 1) && (args[1].charAt(0) <= '2') ) {  // <p 0|1 A...
                power = args[1].charAt(0);
            } else { // <p0|1 A...
                power = args[0].charAt(1);
            }

            mainapp.power_state = args[0].substring(1, 2);
            if (!mainapp.power_state.equals(oldState)) {
                responseStr = "PPA" + args[0].charAt(1);
                mainapp.alert_activities(message_type.RESPONSE, responseStr);
                if (args[0].charAt(1)!='2') {
                    for (int i = 0; i < mainapp.dccexTrackType.length; i++) {
                        mainapp.dccexTrackPower[i] = args[0].charAt(1) - '0';
                        responseStr = "PXX" + ((char) (i + '0')) + args[0].charAt(1);
                        mainapp.alert_activities(message_type.RESPONSE, responseStr);
                    }
                }
            }

        } else { // <p0|1 A|B|C|D|E|F|G|H|MAIN|PROG|DC|DCX>  or  <p 0|1 A|B|C|D|E|F|G|H|MAIN|PROG|DC|DCX>
            int trackOffset = 0;
            char power;
            if ( (args[0].length() == 1) && (args[1].charAt(0) <= '2') ) {  // <p 0|1 A...
                trackOffset = 1;
                power = args[1].charAt(0);
            } else { // <p0|1 A...
                power = args[0].charAt(1);
            }

            if (args[1].length()==1) {  // <p0|1 A|B|C|D|E|F|G|H|>
                int trackNo = args[1].charAt(0) - 'A';
                mainapp.dccexTrackPower[trackNo] = args[0].charAt(1) - '0';
                responseStr = "PXX" + ((char) (trackNo + '0')) + args[0].charAt(1);
                mainapp.alert_activities(message_type.RESPONSE, responseStr);

            } else { // <p0|1 MAIN|PROG|DC|DCX>
                int trackType = 0;
                for (int i=0; i<TRACK_TYPES.length; i++) {
                    String trackTypeStr = args[1+trackOffset];
                    if ( (args.length>(2+trackOffset)) && (args[1+trackOffset].equals("MAIN")) && (args[2+trackOffset].charAt(0)>='A') && (args[2+trackOffset].charAt(0)<='H') ) {
                        trackTypeStr = "AUTO";
                    }
                    if (trackTypeStr.equals(TRACK_TYPES[i])) {

                        trackType = i;
                        break;
                    }
                }
                for (int i=0; i<mainapp.dccexTrackType.length; i++) {
                    if (mainapp.dccexTrackType[i] == trackType) {
                        mainapp.dccexTrackPower[i] = args[0].charAt(1) - '0';
                        responseStr = "PXX" + ((char) (i + '0')) + args[0].charAt(1);
                        mainapp.alert_activities(message_type.RESPONSE, responseStr);
                    }
                }
            }
            boolean globalPowerOn = true;
            boolean globalPowerOff = true;
            for (int i=0; i<mainapp.dccexTrackType.length; i++) {
                if ( (mainapp.dccexTrackAvailable[i]) && (mainapp.dccexTrackType[i] != 0) ) {  // not "NONE"
                    if (mainapp.dccexTrackPower[i] == 1) {
                        globalPowerOff = false;
                    }
                    if (mainapp.dccexTrackPower[i] == 0) {
                        globalPowerOn = false;
                    }
                }
            }

            if (!globalPowerOn && !globalPowerOff) {
                mainapp.power_state = "2";
                mainapp.alert_activities(message_type.RESPONSE, "PPA2"); // inconsistant
            } else {
                if (globalPowerOn) {
                    mainapp.power_state = "1";
                    mainapp.alert_activities(message_type.RESPONSE, "PPA1");
                } else {
                    mainapp.power_state = "0";
                    mainapp.alert_activities(message_type.RESPONSE, "PPA0");
                }
            }
        }
    }

    private static void processDccexRequestCvResponse (String [] args) {
        String cv = "";
        String cvValue = "-1";

        if (args.length==3) {
            cv = args[1];
            cvValue = args[2];
        }

        mainapp.alert_activities(message_type.RECEIVED_CV, cv + "|" + cvValue);  //send response to running activities
    }

    private static void processDccexSensorResponse (String [] args, boolean active) {
        String id = args[1];

        // make sure it is in the sensor list
        boolean valid = true;
        int idValue = 0;
        try {
            idValue = Integer.parseInt(id);
        } catch (Exception ignore) {
            valid = false;
        }
        if (valid) {
            boolean found = false;
            for (int i = 0; i < mainapp.sensorDccexCount; i++) {
                if (mainapp.sensorIdsDccex[i] == idValue) {
                    found = true;
                    break;
                }
            }
            if (!found) {  // received a response from a sensor that was not in the list.  Request the full list again
                if (mainapp.sensorDccexCount < mainapp.prefSensorMaxCount) { // as long as we are not already at max
                    if (!mainapp.sensorMaxCountWarningGiven) {
                        mainapp.sensorMaxCountWarningGiven = true;
                        mainapp.alert_activities(message_type.RECEIVED_ADDITIONAL_SENSOR, id);
                    }
                }
            } else {
                mainapp.alert_activities(message_type.RECEIVED_SENSOR, id + "|" + ((active) ? "1" : "0"));  //send response to running activities
            }
        }
    }

    private static void processDccexSensorDetailsResponse (String [] args) {
        String id = args[1];
        String vpin = args[2];
        String pullup = args[3];
        boolean valid = true;
        int idValue = 0;
        int vpinValue = 0;
        int pullupValue = 0;

        try {
            idValue = Integer.parseInt(id);
            vpinValue = Integer.parseInt(vpin);
            pullupValue = Integer.parseInt(pullup);
        } catch (Exception ignore) {
            valid = false;
        }

        if (valid) {
            boolean found = false;
            for (int i = 0; i < mainapp.sensorDccexCount; i++) {
                if (mainapp.sensorIdsDccex[i] == idValue) {
                    found = true;
                    break;
                }
            }
            if ( (!found) && (mainapp.sensorDccexCount < mainapp.prefSensorMaxCount) ) {
                mainapp.sensorIdsDccex[mainapp.sensorDccexCount] = idValue;
                mainapp.sensorVpinsDccex[mainapp.sensorDccexCount] = vpinValue;
                mainapp.sensorPullupsDccex[mainapp.sensorDccexCount] = pullupValue;
                mainapp.sensorDccexCount++;
                mainapp.alert_activities(message_type.RECEIVED_ADDITIONAL_SENSOR,id);
            } else {
                if (!mainapp.sensorMaxCountWarningGiven) {
                    mainapp.sensorMaxCountWarningGiven = true;
                    threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastTooManySensors, mainapp.prefSensorMaxCount), Toast.LENGTH_LONG);
                }
            }
        }
    }

    private static void processDccexTrackManagerResponse(String [] args) {
        int trackNo;
        String type = args[2];
        if (type.charAt(type.length() - 1) == '+') {
            type = type.substring(0, type.length() - 1);
        }
        if ( (args.length>3) && (args[2].equals("MAIN")) && (args[3].charAt(0)>='A') && (args[3].charAt(0)<='H') ) {
            type = "AUTO";
        }
        if (args.length>=2) {
            trackNo = args[1].charAt(0)-65;
            if ( (trackNo>=0) && (trackNo<= threaded_application.DCCEX_MAX_TRACKS) ) {
                int trackTypeIndex = -1
;               boolean needsId = false;
                for (int i=0; i<TRACK_TYPES.length; i++) {
                    if (type.equals(TRACK_TYPES[i])) {
                        trackTypeIndex = i;
                        needsId = TRACK_TYPES_NEED_ID[i];
                        break;
                    }
                }

                if (trackTypeIndex>=0) {
                    mainapp.dccexTrackType[trackNo] = trackTypeIndex;
                    mainapp.dccexTrackId[trackNo] = "";
                }
                if ( (needsId) && (args.length>=3) ) {
                    mainapp.dccexTrackId[trackNo] = args[3];
                }
                mainapp.dccexTrackAvailable[trackNo] = true;
            }
            mainapp.alert_activities(message_type.RECEIVED_TRACKS, type);  //send response to running activities
        }
    }

    private static void processDccexRequestLocoIdResponse(String [] args) {
        String responseStr = "";

        if (requestLocoIdForWhichThrottleDccex!=-1) { // if -1, request came from the CV read/write screen
            if (!args[1].equals("-1")) {
                String addrStr = args[1];
                if (Integer.parseInt(args[1]) <= 127) {
                    addrStr = "S" + addrStr;
                } else {
                    addrStr = "L" + addrStr;
                }
//                Consist con = mainapp.consists[requestLocoIdForWhichThrottleDccex];
//                if (con.isWaitingOnID()) { //we were waiting for this response to get address
//                    Consist.ConLoco conLoco = new Consist.ConLoco(addrStr);
//                    conLoco.setFunctionLabelDefaults(mainapp, requestLocoIdForWhichThrottleDccex);
//                    //look for RosterEntry which matches address returned
//                    String rn = mainapp.getRosterNameFromAddress(conLoco.getFormatAddress(), true);
//                    if (!rn.equals("")) {
//                        conLoco.setIsFromRoster(true);
//                        conLoco.setRosterName(rn);
//                    }
//                    con.add(conLoco);
//                    con.setWhichSource(addrStr, 1); //entered by address, not roster
//                    con.setConfirmed(addrStr);
//
//                    sendAcquireLoco(addrStr, requestLocoIdForWhichThrottleDccex, 0);
//                    sendJoinDccex();
//                    mainapp.alert_activities(message_type.REQUEST_REFRESH_THROTTLE, "");
//
//                } else {
                    mainapp.alert_activities(message_type.RECEIVED_DECODER_ADDRESS, args[1]);  //send response to running activities
//                }
            }  else {// else {} did not succeed
                threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.dccexRequestLocoIdFailed), Toast.LENGTH_SHORT);
            }

        } else {
            mainapp.alert_activities(message_type.RECEIVED_DECODER_ADDRESS, args[1]);  //send response to running activities
        }

    }

    static void processDccexCurrents(String [] args) {
        if (args.length>1) {
            for (int i=1; i<args.length; i++) {
                mainapp.currentsDccex[threaded_application.PREVIOUS_VALUE][i-1] = mainapp.currentsDccex[threaded_application.LATEST_VALUE][i-1];
                mainapp.currentsDccex[threaded_application.LATEST_VALUE][i-1] = Integer.parseInt(args[i]);
                if (mainapp.currentsDccex[threaded_application.LATEST_VALUE][i-1] > mainapp.currentsHighestDccex[i-1]) {
                    mainapp.currentsHighestDccex[i-1] = mainapp.currentsDccex[threaded_application.LATEST_VALUE][i-1];
                }
            }
            mainapp.alert_activities(message_type.RECEIVED_CURRENTS, "");
        }
    }

    static void processDccexCurrentsMax(String [] args) {
        if (args.length>1) {
            for (int i=1; i<args.length; i++) {
                mainapp.currentsMaxDccex[i-1] = Integer.parseInt(args[i]);
            }
            mainapp.alert_activities(message_type.RECEIVED_CURRENTS_MAX, "");
        }
    }

    private static void processDccexLocos(String [] args) {
        String responseStr;

        int dir = 0;
        int speed = Integer.parseInt(args[3]);
        if (speed >= 128) {
            speed = speed - 128;
            dir = 1;
        }
        if (speed>1) {
            speed = speed - 1; // get round and idiotic design of the speed command
        } else {
            speed=0;
        }

        String addr_str = args[1];
        if (Integer.parseInt(args[1]) <= 127) {
            addr_str = "S" + addr_str;
        } else {
            addr_str = "L" + addr_str;
        }
        for (int throttleIndex = 0; throttleIndex<1; throttleIndex++) {   //loco may be the lead on more that one throttle
//            int whichThrottle = mainapp.getWhichThrottleFromAddress(addr_str, throttleIndex);
            int whichThrottle = 0;
            if (whichThrottle >= 0) {
                responseStr = "M" + mainapp.throttleIntToString(whichThrottle) + "A" + addr_str + "<;>V" + speed;
                mainapp.alert_activities(message_type.RESPONSE, responseStr);  //send response to running activities
                responseStr = "M" + mainapp.throttleIntToString(whichThrottle) + "A" + addr_str + "<;>R" + dir;
                mainapp.alert_activities(message_type.RESPONSE, responseStr);  //send response to running activities

                // Process the functions
                int fnState;
                for (int i = 0; i < 27; i++) {
                    fnState = mainapp.bitExtracted(Integer.parseInt(args[4]), 1, i + 1);
                    processFunctionState(whichThrottle, i, (fnState != 0));
                    responseStr = "M" + mainapp.throttleIntToString(whichThrottle) + "A" + addr_str + "<;>F" + fnState + "" + (i);
                    mainapp.alert_activities(message_type.RESPONSE, responseStr);  //send response to running activities
                }

                throttleIndex = whichThrottle; // skip ahead
            }
        }

        responseStr = "l " + args[1] + " " + speed + " " + dir;
        mainapp.alert_activities(message_type.RECEIVED_LOCO_UPDATE, responseStr);

    } // end processDccexLocos()

    private static boolean processDccexRoster(String [] args) {
        boolean skipAlert = true;

        if ( (args!=null) && (args.length>1)) {
            if ( (args.length<3) || (args[2].charAt(0) != '"') ) {  // loco list
                if (mainapp.rosterStringDccex.isEmpty()) {
                    mainapp.rosterStringDccex = "";
                    mainapp.rosterIDsDccex = new int[args.length - 1];
                    mainapp.rosterLocoNamesDccex = new String[args.length - 1];
                    mainapp.rosterLocoFunctionsDccex = new String[args.length - 1];
                    mainapp.rosterDetailsReceivedDccex = new boolean[args.length - 1];
                    for (int i = 0; i < args.length - 1; i++) { // first will be blank
                        mainapp.rosterIDsDccex[i] = Integer.parseInt(args[i + 1]);
                        mainapp.rosterDetailsReceivedDccex[i] = false;
                        wifiSend("<JR " + args[i + 1] + ">");
                    }
                }
            } else {  // individual loco
                if (mainapp.dccexListsRequested < 3) {
                    if (mainapp.rosterIDsDccex != null) {
                        for (int i = 0; i < mainapp.rosterIDsDccex.length; i++) {
                            if (mainapp.rosterIDsDccex[i] == Integer.parseInt(args[1])) {
                                mainapp.rosterLocoNamesDccex[i] = args[2].substring(1, args[2].length() - 1);
                                mainapp.rosterLocoFunctionsDccex[i] = args[3]; // ignore this
                                mainapp.rosterDetailsReceivedDccex[i] = true;
                                break;
                            }
                        }

                        // check if we have all of them
                        boolean ready = true;
                        for (int i = 0; i < mainapp.rosterIDsDccex.length; i++) {
                            if (!mainapp.rosterDetailsReceivedDccex[i]) {
                                ready = false;
                                break;
                            }
                        }
                        if (ready) {
                            mainapp.rosterStringDccex = "RL" + mainapp.rosterIDsDccex.length;
                            for (int i = 0; i < mainapp.rosterIDsDccex.length; i++) {
                                mainapp.rosterStringDccex = mainapp.rosterStringDccex
                                        + "]\\[" + mainapp.rosterLocoNamesDccex[i]
                                        + "}|{" + mainapp.rosterIDsDccex[i]
                                        + "}|{" + (mainapp.rosterIDsDccex[i] <= 127 ? "S" : "L");
                            }
                            processRosterList(mainapp.rosterStringDccex);
                            mainapp.rosterStringDccex = "";
                            mainapp.dccexListsRequested++;
                            Log.d("EX_Toolbox", "comm_thread.processDccexRoster: Roster complete. Count: " + mainapp.dccexListsRequested);
                        }
                    }

                } // else { // this a request for details on a specific loco - not part of the main roster request
                    // not relevant to EX-Toolbox
//                }
            }
        }
        return skipAlert;
    } // end processDccexRoster()

    private static void processDccexFastClock(String [] args) {
        if (args!=null)  {
            if (args.length == 3) { // <jC mmmm ss>
                mainapp.fastClockSeconds = 0L;
                try {
                    mainapp.fastClockSeconds = Long.parseLong(args[1]) * 60;
                    mainapp.alert_activities(message_type.TIME_CHANGED, "");     //tell activities the time has changed
                } catch (NumberFormatException e) {
                    Log.w("EX_Toolbox", "unable to extract fastClockSeconds from '" + args + "'");
                }
            }
        }
    }

    private static void processDccexTurnouts(String [] args) {

        if (args!=null)  {
            if ( (args.length == 1)  // no Turnouts <jT>
                    || ((args.length == 3) && ((args[2].charAt(0) == 'C') || (args[2].charAt(0) == 'T') || (args[2].charAt(0) == 'X')) ) // <jT id state>     or <jT id X>
                    || ((args.length == 4) && (args[3].charAt(0) == '"') ) ) { // individual turnout  <jT id state "[desc]">
                boolean ready = true;
                boolean noTurnouts = false;

                if ( args.length == 1) { // no turnouts
                    noTurnouts = true;
                } else {
                    for (int i = 0; i < mainapp.turnoutIDsDccex.length; i++) {
                        if (mainapp.turnoutIDsDccex[i] == Integer.parseInt(args[1])) {
                            mainapp.turnoutStatesDccex[i] = args[2];
                            if ((args.length > 3) && (args[3].length() > 2)) {
                                mainapp.turnoutNamesDccex[i] = args[3].substring(1, args[3].length() - 1);
                            } else {
                                mainapp.turnoutNamesDccex[i] = "";
                            }
                            mainapp.turnoutDetailsReceivedDccex[i] = true;
                            break;
                        }
                    }
                    // check if we have all of them
                    for (int i = 0; i < mainapp.turnoutIDsDccex.length; i++) {
                        if (!mainapp.turnoutDetailsReceivedDccex[i]) {
                            ready = false;
                            break;
                        }
                    }
                }
                if (ready) {
                    mainapp.turnoutStringDccex = "PTL";
                    if (!noTurnouts) {
                        for (int i = 0; i < mainapp.turnoutIDsDccex.length; i++) {
                            mainapp.turnoutStringDccex = mainapp.turnoutStringDccex
                                    + "]\\[" + mainapp.turnoutIDsDccex[i]
                                    + "}|{" + mainapp.turnoutNamesDccex[i]
                                    + "}|{" + (mainapp.turnoutStatesDccex[i].equals("T") ? 4 : 2);
                        }
                    }
                    processTurnoutTitles("PTT]\\[Turnouts}|{Turnout]\\["
                            + mainapp.getResources().getString(R.string.dccexTurnoutClosed) + "}|{2]\\["
                            + mainapp.getResources().getString(R.string.dccexTurnoutThrown) + "}|{4]\\["
                            + mainapp.getResources().getString(R.string.dccexTurnoutUnknown) + "}|{1]\\["
                            + mainapp.getResources().getString(R.string.dccexTurnoutInconsistent) + "}|{8");
                    processTurnoutList(mainapp.turnoutStringDccex);
                    mainapp.sendMsg(mainapp.comm_msg_handler, message_type.REQUEST_REFRESH_THROTTLE, "");
                    mainapp.turnoutStringDccex = "";
                    mainapp.dccexListsRequested++;

                    int count = (mainapp.turnoutIDsDccex==null) ? 0 : mainapp.turnoutIDsDccex.length;
                    Log.d("EX_Toolbox", "comm_thread.processDccexTurnouts: Turnouts complete. Count: " + count);
                    mainapp.turnoutsBeingProcessedDccex = false;
                }

            } else { // turnouts list  <jT id1 id2 id3 ...>

                Log.d("EX_Toolbox", "comm_thread.processDccexTurnouts: Turnouts list received.");
                if (!mainapp.turnoutsBeingProcessedDccex) {
                    mainapp.turnoutsBeingProcessedDccex = true;
                    if (mainapp.turnoutStringDccex.isEmpty()) {
                        mainapp.turnoutStringDccex = "";
                        mainapp.turnoutIDsDccex = new int[args.length - 1];
                        mainapp.turnoutNamesDccex = new String[args.length - 1];
                        mainapp.turnoutStatesDccex = new String[args.length - 1];
                        mainapp.turnoutDetailsReceivedDccex = new boolean[args.length - 1];
                        for (int i = 0; i < args.length - 1; i++) { // first will be blank
                            mainapp.turnoutIDsDccex[i] = Integer.parseInt(args[i + 1]);
                            mainapp.turnoutDetailsReceivedDccex[i] = false;
                            wifiSend("<JT " + args[i + 1] + ">");
                        }

                        int count = (mainapp.turnoutIDsDccex==null) ? 0 : mainapp.turnoutIDsDccex.length;
                        Log.d("EX_Toolbox", "comm_thread.processDccexTurnouts: Turnouts list received. Count: " + count);
                    }
                }
            }
        }
    } // end processDccexTurnouts()

    private static void processDccexRoutes(String [] args) {

        if (args != null)  {
            if ( (args.length == 1)  // no Turnouts <jT>
                    || ((args.length == 3) && ((args[2].charAt(0) == 'R') || (args[2].charAt(0) == 'A') || (args[2].charAt(0) == 'X')) )  // <jA id type>  or <jA id X>
                    || ((args.length == 4) && (args[3].charAt(0) == '"') ) ) { // individual routes  <jA id type "[desc]">

                boolean ready = true;
                boolean noRoutes = false;

                if ( args.length == 1) { // no turnouts
                    noRoutes = true;
                } else {
                    for (int i = 0; i < mainapp.routeIDsDccex.length; i++) {
                        if (mainapp.routeIDsDccex[i] == Integer.parseInt(args[1])) {
                            mainapp.routeTypesDccex[i] = args[2];
                            mainapp.routeNamesDccex[i] = args[3].substring(1, args[3].length() - 1);
                            mainapp.routeDetailsReceivedDccex[i] = true;
                            break;
                        }
                    }
                    // check if we have all of them

                    for (int i = 0; i < mainapp.routeIDsDccex.length; i++) {
                        if (!mainapp.routeDetailsReceivedDccex[i]) {
                            ready = false;
                            break;
                        }
                    }
                }
                if (ready) {
                    mainapp.routeStringDccex = "PRL";
                    if (!noRoutes) {
                        for (int i = 0; i < mainapp.routeIDsDccex.length; i++) {
                            mainapp.routeStringDccex = mainapp.routeStringDccex
                                    + "]\\[" + mainapp.routeIDsDccex[i]
                                    + "}|{" + mainapp.routeNamesDccex[i]
                                    + "}|{" + (mainapp.routeTypesDccex[i].equals("R") ? 2 : 4);  //2=Route 4=Automation
                        }
                    }
                    processRouteTitles("PRT]\\[Routes}|{Route]\\["
                            + mainapp.getResources().getString(R.string.dccexRouteSet)+"}|{2]\\["
                            + mainapp.getResources().getString(R.string.dccexRouteHandoff) + "}|{4");
                    processRouteList(mainapp.routeStringDccex);
                    mainapp.sendMsg(mainapp.comm_msg_handler, message_type.REQUEST_REFRESH_THROTTLE, "");
                    mainapp.routeStringDccex = "";
                    mainapp.dccexListsRequested++;

                    int count = (mainapp.routeIDsDccex==null) ? 0 : mainapp.routeIDsDccex.length;
                    Log.d("EX_Toolbox", "comm_thread.processDccexRoutes: Routes complete. Count: " + count);
                    mainapp.routesBeingProcessedDccex = false;
                }

            } else { // routes list   <jA id1 id2 id3 ...>   or <jA> for empty

                Log.d("EX_Toolbox", "comm_thread.processDccexRoutes: Routes list received.");
                if (!mainapp.routesBeingProcessedDccex) {
                    mainapp.routesBeingProcessedDccex = true;
                    if (mainapp.routeStringDccex.isEmpty()) {
                        mainapp.routeStringDccex = "";
                        mainapp.routeIDsDccex = new int[args.length - 1];
                        mainapp.routeNamesDccex = new String[args.length - 1];
                        mainapp.routeTypesDccex = new String[args.length - 1];
                        mainapp.routeStatesDccex = new String[args.length - 1];
                        mainapp.routeDetailsReceivedDccex = new boolean[args.length - 1];
                        for (int i = 0; i < args.length - 1; i++) { // first will be blank
                            mainapp.routeIDsDccex[i] = Integer.parseInt(args[i + 1]);
                            mainapp.routeDetailsReceivedDccex[i] = false;
                            wifiSend("<JA " + args[i + 1] + ">");
                        }

                        int count = (mainapp.routeIDsDccex==null) ? 0 : mainapp.routeIDsDccex.length;
                        Log.d("EX_Toolbox", "comm_thread.processDccexRoutes: Routes list received. Count: " + count);
                    }
                }
            }
        }
    } // end processDccexRoutes()

    /* ***********************************  *********************************** */

    //parse roster functions list into appropriate app variable array
    //  //RF29}|{4805(L)]\[Light]\[Bell]\[Horn]\[Air]\[Uncpl]\[BrkRls]\[]\[]\[]\[]\[]\[]\[Engine]\[]\[]\[]\[]\[]\[BellSel]\[HornSel]\[]\[]\[]\[]\[]\[]\[]\[]\[
    static void processRosterFunctionString(String responseStr, int whichThrottle) {

        Log.d("EX_Toolbox", "comm_thread.processRosterFunctionString: processing function labels for " + mainapp.throttleIntToString(whichThrottle));
        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //split into list of labels

        //populate a temp label array from RF command string
        LinkedHashMap<Integer, String> function_labels_temp = new LinkedHashMap<>();
        int i = 0;
        for (String ts : ta) {
            if (i > 0 && !"".equals(ts)) { //skip first chunk, which is length, and skip any blank entries
                function_labels_temp.put(i - 1, ts); //index is hashmap key, value is label string
            }  //end if i>0
            i++;
        }  //end for

        //set the appropriate global variable from the temp
        mainapp.function_labels[whichThrottle] = function_labels_temp;

    }

    //parse roster list into appropriate app variable array
    //  RL2]\[NS2591}|{2591}|{L]\[NS4805}|{4805}|{L
    static void processRosterList(String responseStr) {
        //clear the global variable
        mainapp.roster_entries = Collections.synchronizedMap(new LinkedHashMap<String, String>());
        //todo   RDB why don't we just clear the existing map with roster_entries.clear() instead of disposing and creating a new instance?

        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //initial separation
        //initialize app arrays (skipping first)
        int i = 0;
        for (String ts : ta) {
            if (i > 0) { //skip first chunk
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split these into name, address and length
                try {
                    mainapp.roster_entries.put(tv[0], tv[1] + "(" + tv[2] + ")"); //roster name is hashmap key, value is address(L or S), e.g.  2591(L)
                } catch (Exception e) {
                    Log.d("EX_Toolbox", "comm_thread.processRosterList caught Exception");  //ignore any bad stuff in roster entries
                }
            }  //end if i>0
            i++;
        }  //end for

    }

    //parse consist list into appropriate mainapp hashmap
    //RCD}|{88(S)}|{Consist Name]\[2591(L)}|{true]\[3(S)}|{true]\[4805(L)}|{true
    static void processConsistList(String responseStr) {
        String consist_addr = null;
        StringBuilder consist_desc = new StringBuilder();
        String consist_name = "";
        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //initial separation
        String plus = ""; //plus sign for a separator
        //initialize app arrays (skipping first)
        int i = 0;
        for (String ts : ta) {
            if (i == 0) { //first chunk is a "header"
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split header chunk into header, address and name
                consist_addr = tv[1];
                consist_name = tv[2];
            } else {  //list of locos in consist
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split these into loco address and direction
                consist_desc.append(plus).append(tv[0]);
                plus = "+";
            }  //end if i==0
            i++;
        }  //end for
        Log.d("EX_Toolbox", "comm_thread.processConsistList: consist header, addr='" + consist_addr
                + "', name='" + consist_name + "', desc='" + consist_desc + "'");
        //don't add empty consists to list
        if (mainapp.consist_entries != null && consist_desc.length() > 0) {
            mainapp.consist_entries.put(consist_addr, consist_desc.toString());
        } else {
            Log.d("EX_Toolbox", "comm_thread.processConsistList: skipping empty consist '" + consist_name + "'");
        }
    }

    //clear out any stored consists
    static void clearConsistList() {
        if (mainapp.consist_entries!=null) mainapp.consist_entries.clear();
    }

    static int findTurnoutPos(String systemName) {
        int pos = -1;
        for (String sn : mainapp.to_system_names) { //TODO: rewrite for better lookup
            pos++;
            if (sn != null && sn.equals(systemName)) {
                break;
            }
        }
        return pos;
    }

    //parse turnout change to update mainapp array entry
    //  PTA<NewState><SystemName>
    //  PTA2LT12
    static void processTurnoutChange(String responseStr) {
        if (mainapp.to_system_names == null) return;  //ignore if turnouts not defined
        String newState = responseStr.substring(3, 4);
        String systemName = responseStr.substring(4);
//        int pos = -1;
//        for (String sn : mainapp.to_system_names) {
//            pos++;
//            if (sn != null && sn.equals(systemName)) {
//                break;
//            }
//        }
        int pos = findTurnoutPos(systemName);
        if (pos >= 0 && pos <= mainapp.to_system_names.length) {  //if found, update to new value
            mainapp.to_states[pos] = newState;
        }
    }  //end of processTurnoutChange


    static void processTurnoutDetails(String [] args) {
        if (args[2].equals("SERVO")) {
            String responseStr = args[1] + " " + args[2] + " " + args[3] + " " + args[4] + " " + args[5] + " " + args[6] + " " + args[7];
            mainapp.alert_activities(message_type.RECEIVED_SERVO_DETAILS, responseStr);
        } // ignore the others
    }

    //parse turnout list into appropriate app variable array
    //  PTL[<SystemName><UserName><State>repeat] where state 1=Unknown. 2=Closed, 4=Thrown
    //  PTL]\[LT12}|{my12}|{1
    static void processTurnoutList(String responseStr) {

        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //initial separation
        //initialize app arrays (skipping first)
        mainapp.to_system_names = new String[ta.length - 1];
        mainapp.to_user_names = new String[ta.length - 1];
        mainapp.to_states = new String[ta.length - 1];
        int i = 0;
        for (String ts : ta) {
            if (i > 0) { //skip first chunk, just message id
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split these into 3 parts, key and value
                if (tv.length == 3) { //make sure split worked
                    mainapp.to_system_names[i - 1] = tv[0];
                    mainapp.to_user_names[i - 1] = tv[1];
                    mainapp.to_states[i - 1] = tv[2];
                }
            }  //end if i>0
            i++;
        }  //end for

    }

    static void processTurnoutTitles(String responseStr) {
        //PTT]\[Turnouts}|{Turnout]\[Closed}|{2]\[Thrown}|{4

        //clear the global variable
        mainapp.to_state_names = new HashMap<>();

        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //initial separation
        //initialize app arrays (skipping first)
        int i = 0;
        for (String ts : ta) {
            if (i > 1) { //skip first 2 chunks
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split these into value and key
                mainapp.to_state_names.put(tv[1], tv[0]);
            }  //end if i>0
            i++;
        }  //end for

    }

    //parse route list into appropriate app variable array
    //  PRA<NewState><SystemName>
    //  PRA2LT12
    static void processRouteChange(String responseStr) {
        String newState = responseStr.substring(3, 4);
        String systemName = responseStr.substring(4);
        int pos = -1;
        for (String sn : mainapp.rt_system_names) {
            pos++;
            if (sn != null && sn.equals(systemName)) {
                break;
            }
        }
        if (pos >= 0 && pos <= mainapp.rt_system_names.length) {  //if found, update to new value
            mainapp.rt_states[pos] = newState;
        }
    }  //end of processRouteChange

    //parse route list into appropriate app variable array
    //  PRL[<SystemName><UserName><State>]repeat where state 1=Unknown,2=Active,4=Inactive,8=Inconsistent
    //  PRL]\[LT12}|{my12}|{1
    static void processRouteList(String responseStr) {

        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //initial separation
        //initialize app arrays (skipping first)
        mainapp.rt_system_names = new String[ta.length - 1];
        mainapp.rt_user_names = new String[ta.length - 1];
        mainapp.rt_states = new String[ta.length - 1];
        int i = 0;
        for (String ts : ta) {
            if (i > 0) { //skip first chunk, just message id
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split these into 3 parts, key and value
                mainapp.rt_system_names[i - 1] = tv[0];
                mainapp.rt_user_names[i - 1] = tv[1];
                mainapp.rt_states[i - 1] = tv[2];
            }  //end if i>0
            i++;
        }  //end for

    }

    static void processRouteTitles(String responseStr) {
        //PRT

        //clear the global variable
        mainapp.rt_state_names = new HashMap<>();

        String[] ta = threaded_application.splitByString(responseStr, "]\\[");  //initial separation
        //initialize app arrays (skipping first)
        int i = 0;
        for (String ts : ta) {
            if (i > 1) { //skip first 2 chunks
                String[] tv = threaded_application.splitByString(ts, "}|{");  //split these into value and key
                mainapp.rt_state_names.put(tv[1], tv[0]);
            }  //end if i>0
            i++;
        }  //end for
    }

    //parse function state string into appropriate app variable array
    static void processFunctionState(int whichThrottle, Integer fn, boolean fState) {

        boolean skip = (fn > 2);

        if (!skip) {
            try {
                mainapp.function_states[whichThrottle][fn] = fState;
            } catch (Exception ignored) {
            }
        }
    }

    /** @noinspection UnnecessaryUnicodeEscape*/ //
    // wifiSend(String msg)
    //
    //send formatted msg to the socket using multithrottle format
    //  intermessage gap enforced by requeueing messages as needed
    protected static void wifiSend(String msg) {
        Log.d("EX_Toolbox", "comm_thread.wifiSend: WiT send '" + msg + "'");
        if (msg == null) { //exit if no message
            Log.d("EX_Toolbox", "comm_thread.wifiSend: --> null msg");
            return;
        } else if (socketWiT == null) {
            Log.e("EX_Toolbox", "comm_thread.wifiSend: socketWiT is null, message '" + msg + "' not sent!");
            return;
        }

        long now = System.currentTimeMillis();
        long lastGap = now - lastSentMs;

        //send if sufficient gap between messages or msg is timingSensitive, requeue if not
        if (lastGap >= threaded_application.WiThrottle_Msg_Interval || timingSensitive(msg)) {
            //perform the send
            Log.d("EX_Toolbox", "comm_thread.wifiSend: " + (mainapp.isDccex ? "DCC-EX" : "") + "           -->:" + msg.replaceAll("\n", "\u21B5") + " (" + lastGap + ")"); //replace newline with cr arrow
            lastSentMs = now;
            socketWiT.Send(msg);

            if (mainapp.dccexScreenIsOpen) { // only relevant to some DCC-EX commands that we want to see in the DCC-EC Screen.
                mainapp.sendMsg(mainapp.comm_msg_handler, message_type.DCCEX_COMMAND_ECHO, msg);
            }
        } else {
            //requeue this message
            int nextGap = Math.max((int) (lastQueuedMs - now), 0) + (threaded_application.WiThrottle_Msg_Interval + 5); //extra 5 for processing
            Log.d("EX_Toolbox", "comm_thread.wifiSend: requeue:" + msg.replaceAll("\n", "\u21B5") +
                    ", lastGap=" + lastGap + ", nextGap=" + nextGap); //replace newline with cr arrow
            mainapp.sendMsgDelay(mainapp.comm_msg_handler, nextGap, message_type.WIFI_SEND, msg);
            lastQueuedMs = now + nextGap;
        }
    }  //end wifiSend()

    /* true indicates that message should NOT be requeued as the timing of this message
         is critical.
     */
    private static boolean timingSensitive(String msg) {
        boolean ret = false;
//        if (!msg.matches("^<[oD]")) {  // can't figure out why this doesn't work
        if ( (msg.contains("<o")) || (msg.contains("<D")) ) {
            ret = true;
            Log.d("EX_Toolbox", "comm_thread.timingSensitive: timeSensitive msg, not requeueing: " + msg);
        } else {
            Log.d("EX_Toolbox", "comm_thread.timingSensitive: timeSensitive msg, can requeue: " + msg);
        }
        return ret;
    }

    public void run() {
        Looper.prepare();
        mainapp.comm_msg_handler = new comm_handler(mainapp, prefs, this);
        Looper.loop();
        Log.d("EX_Toolbox", "comm_thread.run() exit");
    }

    /* ******************************************************************************************** */
    /* ******************************************************************************************** */

    static class socketWifi extends Thread {
        InetAddress host_address;
        Socket clientSocket = null;
        BufferedReader inputBR = null;
        PrintWriter outputPW = null;
        private volatile boolean endRead = false;           //signals rcvr to terminate
        private volatile boolean socketGood = false;        //indicates socket condition
        private volatile boolean inboundTimeout = false;    //indicates inbound messages are not arriving from WiT
        private boolean firstConnect = false;               //indicates initial socket connection was achieved
        private int connectTimeoutMs = 3000; //connection timeout in milliseconds
        private int socketTimeoutMs = 500; //socket timeout in milliseconds

        private final int MAX_INBOUND_TIMEOUT_RETRIES = 2;
        private int inboundTimeoutRetryCount = 0;           // number of consecutive inbound timeouts
        private boolean inboundTimeoutRecovery = false;     // attempting to force WiT to respond


        socketWifi() {
            super("socketWifi");
        }

        public boolean connect() {

            //use local socketOk instead of setting socketGood so that the rcvr doesn't resume until connect() is done
            boolean socketOk = HaveNetworkConnection();

            connectTimeoutMs = Integer.parseInt(prefs.getString("prefConnectTimeoutMs", mainapp.getResources().getString(R.string.prefConnectTimeoutMsDefaultValue)));
            socketTimeoutMs = Integer.parseInt(prefs.getString("prefSocketTimeoutMs", mainapp.getResources().getString(R.string.prefSocketTimeoutMsDefaultValue)));

            //validate address
            if (socketOk) {
                try {
                    host_address = InetAddress.getByName(mainapp.host_ip);
                } catch (UnknownHostException except) {
//                        show_toast_message("Can't determine IP address of " + host_ip, Toast.LENGTH_LONG);
                    threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppCantDetermineIp, mainapp.host_ip), Toast.LENGTH_SHORT);
                    socketOk = false;
                }
            }

            //socket
            if (socketOk) {
                try {
                    //look for someone to answer on specified socket, and set timeout
                    Log.d("EX_Toolbox", "comm_thread.socketWifi: Opening socket, connectTimeout=" + connectTimeoutMs + " and socketTimeout=" + socketTimeoutMs);
                    clientSocket = new Socket();
                    InetSocketAddress sa = new InetSocketAddress(mainapp.host_ip, mainapp.port);
                    clientSocket.connect(sa, connectTimeoutMs);
                    Log.d("EX_Toolbox", "comm_thread.socketWifi: Opening socket: Connect successful.");
                    clientSocket.setSoTimeout(socketTimeoutMs);
                    Log.d("EX_Toolbox", "comm_thread.socketWifi: Opening socket: set timeout successful.");
                } catch (Exception except) {
                    if (!firstConnect) {
                        threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppCantConnect,
                                mainapp.host_ip, Integer.toString(mainapp.port), mainapp.client_address, except.getMessage()), Toast.LENGTH_LONG);
                    }
                    if ((!mainapp.client_type.equals("WIFI")) && (mainapp.prefAllowMobileData)) { //show additional message if using mobile data
                        Log.d("EX_Toolbox", "comm_thread.socketWifi: Opening socket: Using mobile network, not WIFI. Check your WiFi settings and Preferences.");
                        threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppNotWIFI, mainapp.client_type), Toast.LENGTH_LONG);
                    }
                    socketOk = false;
                }
            }

            //rcvr
            if (socketOk) {
                try {
                    inputBR = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                } catch (IOException except) {
                    threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppErrorInputStream, except.getMessage()), Toast.LENGTH_SHORT);
                    socketOk = false;
                }
            }

            //start the socketWifi thread.
            if (socketOk) {
                if (!this.isAlive()) {
                    endRead = false;
                    try {
                        this.start();
                    } catch (IllegalThreadStateException except) {
                        //ignore "already started" errors
                        threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppErrorStartingSocket, except.getMessage()), Toast.LENGTH_SHORT);
                    }
                }
            }

            //xmtr
            if (socketOk) {
                try {
                    outputPW = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true);
                    if (outputPW.checkError()) {
                        socketOk = false;
                    }
                } catch (IOException e) {
                    threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppErrorCreatingOutputStream, e.getMessage()), Toast.LENGTH_SHORT);
                    socketOk = false;
                }
            }
            socketGood = socketOk;
            if (socketOk)
                firstConnect = true;
            return socketOk;
        }

        public void disconnect(boolean shutdown) {
            disconnect(shutdown, false);
        }

        public void disconnect(boolean shutdown, boolean fastShutdown) {
            if (shutdown) {
                endRead = true;
                if (!fastShutdown) {
                    for (int i = 0; i < 5 && this.isAlive(); i++) {
                        try {
                            Thread.sleep(connectTimeoutMs);     //  give run() a chance to see endRead and exit
                        } catch (InterruptedException e) {
                            threaded_application.safeToast(threaded_application.context.getResources().getString(R.string.toastThreadedAppErrorSleepingThread, e.getMessage()), Toast.LENGTH_SHORT);
                        }
                    }
                }
            }

            socketGood = false;

            //close socket
            if (clientSocket != null) {
                try {
                    clientSocket.close();
                } catch (Exception e) {
                    Log.d("EX_Toolbox", "comm_thread.socketWifi: Error closing the Socket: " + e.getMessage());
                }
            }
        }

        //read the input buffer
        public void run() {
            String str;
            //continue reading until signaled to exit by endRead
            while (!endRead) {
                if (socketGood) {        //skip read when the socket is down
                    try {
                        if ((str = inputBR.readLine()) != null) {
                            if (str.length() > 0) {
                                heart.restartInboundInterval();
                                clearInboundTimeout();
                                processWifiResponse(str);
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        socketGood = this.SocketCheck();
                    } catch (IOException e) {
                        if (socketGood) {
                            Log.d("EX_Toolbox", "comm_thread.run(): WiT rcvr error.");
                            socketGood = false;     //input buffer error so force reconnection on next send
                        }
                    }
                }
                if (!socketGood) {
                    SystemClock.sleep(500L);        //don't become compute bound here when the socket is down
                }
            }
            heart.stopHeartbeat();
            Log.d("EX_Toolbox", "comm_thread.run(): socketWifi exit.");
        }

        @SuppressLint("StringFormatMatches")
        void Send(String msg) {
            boolean reconInProg = false;
            //reconnect socket if needed
            if (!socketGood || inboundTimeout) {
                String status;
                if (mainapp.client_address == null) {
                    status = threaded_application.context.getResources().getString(R.string.statusThreadedAppNotConnected);
                    Log.d("EX_Toolbox", "comm_thread.send(): WiT send reconnection attempt.");
                } else if (inboundTimeout) {
                    status = threaded_application.context.getResources().getString(R.string.statusThreadedAppNoResponse, mainapp.host_ip, Integer.toString(mainapp.port), heart.getInboundInterval());
                    Log.d("EX_Toolbox", "comm_thread.send(): WiT receive reconnection attempt.");
                } else {
                    status = threaded_application.context.getResources().getString(R.string.statusThreadedAppUnableToConnect, mainapp.host_ip, Integer.toString(mainapp.port), mainapp.client_address);
                    Log.d("EX_Toolbox", "comm_thread.send(): WiT send reconnection attempt.");
                }
                socketGood = false;

                mainapp.sendMsg(mainapp.comm_msg_handler, message_type.WIT_CON_RETRY, status);

                //perform the reconnection sequence
                this.disconnect(false);             //clean up socket but do not shut down the receiver
                this.connect();                     //attempt to reestablish connection
                reconInProg = true;
            }

            //try to send the message
            if (socketGood) {
                try {
                    outputPW.println(msg);
                    outputPW.flush();
                    heart.restartOutboundInterval();

                    // if we get here without an exception then the socket is ok
                    if (reconInProg) {
                        String status = "Connected to WiThrottle Server at " + mainapp.host_ip + ":" + mainapp.port;
                        mainapp.sendMsg(mainapp.comm_msg_handler, message_type.WIT_CON_RECONNECT, status);
                        Log.d("EX_Toolbox", "comm_thread.send(): WiT reconnection successful.");
                        clearInboundTimeout();
                        heart.restartInboundInterval();     //socket is good so restart inbound heartbeat timer
                    }
                } catch (Exception e) {
                    Log.d("EX_Toolbox", "comm_thread.send(): WiT xmtr error.");
                    socketGood = false;             //output buffer error so force reconnection on next send
                }
            }

            if (!socketGood) {
                mainapp.comm_msg_handler.postDelayed(heart.outboundHeartbeatTimer, 500L);   //try connection again in 0.5 second
            }
        }

        // Attempt to determine if the socket connection is still good.
        // unfortunately isConnected returns true if the Socket was disconnected other than by calling close()
        // so on signal loss it still returns true.
        // Eventually we just try to send and handle the IOException if the socket was disconnected.
        boolean SocketCheck() {
            boolean status = clientSocket.isConnected() && !clientSocket.isInputShutdown() && !clientSocket.isOutputShutdown();
            if (status)
                status = HaveNetworkConnection();   // can't trust the socket flags so try something else...
            return status;
        }

        // temporary - SocketCheck should determine whether socket connection is good however socket flags sometimes do not get updated
        // so it doesn't work.  This is better than nothing though?
        private boolean HaveNetworkConnection() {
            boolean haveConnectedWifi = false;
            boolean haveConnectedMobile = false;
            mainapp.prefAllowMobileData = prefs.getBoolean("prefAllowMobileData", false);

            final ConnectivityManager cm = (ConnectivityManager) mainapp.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo[] netInfo = cm.getAllNetworkInfo();
            for (NetworkInfo ni : netInfo) {
                if ("WIFI".equalsIgnoreCase(ni.getTypeName()))

                    if (!mainapp.prefAllowMobileData) {
                        // attempt to resolve the problem where some devices won't connect over wifi unless mobile data is turned off
                        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                                && (!mainapp.haveForcedWiFiConnection)) {

                            Log.d("EX_Toolbox", "comm_thread.HaveNetworkConnection: NetworkRequest.Builder");
                            NetworkRequest.Builder request = new NetworkRequest.Builder();
                            request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

                            cm.registerNetworkCallback(request.build(), new ConnectivityManager.NetworkCallback() {
                                @Override
                                public void onAvailable(Network network) {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                                        ConnectivityManager.setProcessDefaultNetwork(network);
                                    } else {
                                        cm.bindProcessToNetwork(network);  //API23+
                                    }
                                }
                            });
                            mainapp.haveForcedWiFiConnection = true;
                        }
                    }

                if (ni.isConnected()) {
                    haveConnectedWifi = true;
                } else {
                    // attempt to resolve the problem where some devices won't connect over wifi unless mobile data is turned off
                    if (mainapp.prefAllowMobileData) {
                        haveConnectedWifi = true;
                    }
                }
                if ("MOBILE".equalsIgnoreCase(ni.getTypeName()))
                    if ((ni.isConnected()) && (mainapp.prefAllowMobileData)) {
                        haveConnectedMobile = true;
                    }
            }
            return haveConnectedWifi || haveConnectedMobile;
        }

        boolean SocketGood() {
            return this.socketGood;
        }

        void InboundTimeout() {
            if (++inboundTimeoutRetryCount >= MAX_INBOUND_TIMEOUT_RETRIES) {
                Log.d("EX_Toolbox", "comm_thread.InboundTimeout: WiT max inbound timeouts");
                inboundTimeout = true;
                inboundTimeoutRetryCount = 0;
                inboundTimeoutRecovery = false;
                // force a send to start the reconnection process
                mainapp.comm_msg_handler.postDelayed(heart.outboundHeartbeatTimer, 200L);
            } else {
                Log.d("EX_Toolbox", "comm_thread.InboundTimeout: WiT inbound timeout " +
                        Integer.toString(inboundTimeoutRetryCount) + " of " + MAX_INBOUND_TIMEOUT_RETRIES);
                // heartbeat should trigger a WiT reply so force that now
                inboundTimeoutRecovery = true;
                mainapp.comm_msg_handler.post(heart.outboundHeartbeatTimer);
            }
        }

        void clearInboundTimeout() {
            inboundTimeout = false;
            inboundTimeoutRecovery = false;
            inboundTimeoutRetryCount = 0;
        }
    }

    /* ******************************************************************************************** */

    static class heartbeat {
        //  outboundHeartbeat - send a periodic heartbeat to WiT to show that ED is alive.
        //  inboundHeartbeat - WiT doesn't send a heartbeat to ED, so send a periodic message to WiT that requires a response.
        //
        //  If the HeartbeatValueFromServer is 0 then set heartbeatOutboundInterval = DEFAULT_OUTBOUND_HEARTBEAT_INTERVAL,
        //    and set heartbeatInboundInterval = 0, to disable the inbound heartbeat checks
        //
        //  Otherwise, set heartbeatOutboundInterval to HeartbeatValueFromServer * HEARTBEAT_RESPONSE_ALLOWANCE,
        //    and set heartbeatInboundInterval to HeartbeatValueFromServer / HEARTBEAT_RESPONSE_ALLOWANCE
        //
        //  Insure both values are between MIN_OUTBOUND_HEARTBEAT_INTERVAL and MAX_OUTBOUND_HEARTBEAT_INTERVAL

        private int heartbeatIntervalSetpoint = 0;      //WiT heartbeat interval in msec
        private int heartbeatOutboundInterval = 0;      //sends outbound heartbeat message at this rate (msec)
        private int heartbeatInboundInterval = 0;       //alerts user if there was no inbound traffic for this long (msec)

        public boolean isHeartbeatSent() {
            return heartbeatSent;
        }

        public void setHeartbeatSent(boolean heartbeatSent) {
            this.heartbeatSent = heartbeatSent;
        }

        private boolean heartbeatSent = false;

        int getInboundInterval() {
            return heartbeatInboundInterval;
        }

        /***
         * startHeartbeat(timeoutInterval in milliseconds)
         * calcs the inbound and outbound intervals and starts the beating
         *
         * @param timeoutInterval the WiT timeoutInterval in milliseconds
         */
        void startHeartbeat(int timeoutInterval) {
            //update interval timers only when the heartbeat timeout interval changed
            mainapp.prefHeartbeatResponseFactor = threaded_application.getIntPrefValue(prefs, "prefHeartbeatResponseFactor", mainapp.getApplicationContext().getResources().getString(R.string.prefHeartbeatResponseFactorDefaultValue));

            if (timeoutInterval != heartbeatIntervalSetpoint) {
                heartbeatIntervalSetpoint = timeoutInterval;

                // outbound interval (in ms)
                int outInterval;
                if (heartbeatIntervalSetpoint == 0) {   //wit heartbeat is disabled so use default outbound heartbeat
                    outInterval = threaded_application.DEFAULT_OUTBOUND_HEARTBEAT_INTERVAL;
                } else {
//                        outInterval = (int) (heartbeatIntervalSetpoint * HEARTBEAT_RESPONSE_FACTOR);
                    outInterval = (int) (heartbeatIntervalSetpoint * ( (double) mainapp.prefHeartbeatResponseFactor) / 100);
                    //keep values in a reasonable range
                    if (outInterval < threaded_application.MIN_OUTBOUND_HEARTBEAT_INTERVAL)
                        outInterval = threaded_application.MIN_OUTBOUND_HEARTBEAT_INTERVAL;
                    if (outInterval > threaded_application.MAX_OUTBOUND_HEARTBEAT_INTERVAL)
                        outInterval = threaded_application.MAX_OUTBOUND_HEARTBEAT_INTERVAL;
                }
                heartbeatOutboundInterval = outInterval;

                // inbound interval
                int inInterval = mainapp.heartbeatInterval;
                if (heartbeatIntervalSetpoint == 0) {    // wit heartbeat is disabled so disable inbound heartbeat
                    inInterval = 0;
                } else {
                    if (inInterval < threaded_application.MIN_INBOUND_HEARTBEAT_INTERVAL)
                        inInterval = threaded_application.MIN_INBOUND_HEARTBEAT_INTERVAL;
                    if (inInterval < outInterval)
//                            inInterval = (int) (outInterval / HEARTBEAT_RESPONSE_FACTOR);
                        inInterval = (int) (outInterval / ( ((double) mainapp.prefHeartbeatResponseFactor) / 100) );
                    if (inInterval > threaded_application.MAX_INBOUND_HEARTBEAT_INTERVAL)
                        inInterval = threaded_application.MAX_INBOUND_HEARTBEAT_INTERVAL;
                }
                heartbeatInboundInterval = inInterval;
                //sInboundInterval = Integer.toString(inInterval);    // seconds

                restartOutboundInterval();
                restartInboundInterval();
            }
        }

        //restartOutboundInterval()
        //restarts the outbound interval timing - call this after sending anything to WiT that requires a response
        void restartOutboundInterval() {
            mainapp.comm_msg_handler.removeCallbacks(outboundHeartbeatTimer);                   //remove any pending requests
            if (heartbeatOutboundInterval > 0) {
                mainapp.comm_msg_handler.postDelayed(outboundHeartbeatTimer, heartbeatOutboundInterval);    //restart interval
            }
        }

        //restartInboundInterval()
        //restarts the inbound interval timing - call this after receiving anything from WiT
        void restartInboundInterval() {
            mainapp.comm_msg_handler.removeCallbacks(inboundHeartbeatTimer);
            if (heartbeatInboundInterval > 0) {
                mainapp.comm_msg_handler.postDelayed(inboundHeartbeatTimer, heartbeatInboundInterval);
            }
        }

        void stopHeartbeat() {
            mainapp.comm_msg_handler.removeCallbacks(outboundHeartbeatTimer);           //remove any pending requests
            mainapp.comm_msg_handler.removeCallbacks(inboundHeartbeatTimer);
            heartbeatIntervalSetpoint = 0;
            Log.d("EX_Toolbox", "comm_thread.stopHeartbeat: heartbeat stopped.");
        }

        //outboundHeartbeatTimer()
        //sends a periodic message to WiT
        private final Runnable outboundHeartbeatTimer = new Runnable() {
            @Override
            public void run() {
                mainapp.comm_msg_handler.removeCallbacks(this);             //remove pending requests
                if (heartbeatIntervalSetpoint != 0) {
                    boolean anySent = false;
                    if (!mainapp.isDccex) {
//                        for (int i = 0; i < mainapp.numThrottles; i++) {
                        for (int i = 0; i < 1; i++) {
                            if (mainapp.consists[i].isActive()) {
                                sendRequestSpeedAndDir(i);
                                anySent = true;
                            }
                        }
                    }
                    // prior to JMRI 4.20 there were cases where WiT might not respond to
                    // speed and direction request.  If inboundTimeout handling is in progress
                    // then we always send the Throttle Name to ensure a response
                    if (!anySent || (mainapp.getServerType().isEmpty() && socketWiT.inboundTimeoutRecovery)) {
                        sendThrottleName(false);    //send message that will get a response
                    }
                    mainapp.comm_msg_handler.postDelayed(this, heartbeatOutboundInterval);   //set next beat
                }
            }
        };

        //inboundHeartbeatTimer()
        //display an alert message when there is no inbound traffic from WiT within required interval
        private final Runnable inboundHeartbeatTimer = new Runnable() {
            @Override
            public void run() {
                mainapp.comm_msg_handler.removeCallbacks(this); //remove pending requests
                if (heartbeatIntervalSetpoint != 0) {
                    if (socketWiT != null && socketWiT.SocketGood()) {
                        socketWiT.InboundTimeout();
                    }
                    mainapp.comm_msg_handler.postDelayed(this, heartbeatInboundInterval);    //set next inbound timeout
                }
            }
        };
    }

    /* ******************************************************************************************** */
    /* ******************************************************************************************** */


    public static void displayCommands(String msg, boolean inbound) {
        @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        String currentTime = sdf.format(new Date());

        if (inbound) {
            mainapp.DccexResponsesListHtml.add("<small><small>" + currentTime + " </small></small> ◄ : <b>" + Html.escapeHtml(msg) + "</b><br />");
        } else {
//            dccexSendsListHtml.add("<small><small>" + currentTime + " </small></small> ► : &nbsp &nbsp &nbsp &nbsp &nbsp &nbsp &nbsp &nbsp <i>" + Html.escapeHtml(msg) + "</i><br />");
            mainapp.dccexSendsListHtml.add("<small><small>" + currentTime + " </small></small> ► : <i>" + Html.escapeHtml(msg) + "</i><br />");
        }
        if (mainapp.DccexResponsesListHtml.size()>80) {
            mainapp.DccexResponsesListHtml.remove(0);
        }
        if (mainapp.dccexSendsListHtml.size()>60) {
            mainapp.dccexSendsListHtml.remove(0);
        }

        mainapp.dccexResponsesStr ="<p>";
        for (int i=0; i<mainapp.DccexResponsesListHtml.size(); i++) {
            mainapp.dccexResponsesStr = mainapp.DccexResponsesListHtml.get(i) + mainapp.dccexResponsesStr;
        }
        mainapp.dccexResponsesStr = mainapp.dccexResponsesStr + "</p>";

        mainapp.dccexSendsStr ="<p>";
        for (int i=0; i<mainapp.dccexSendsListHtml.size(); i++) {
            mainapp.dccexSendsStr = mainapp.dccexSendsListHtml.get(i) + mainapp.dccexSendsStr;
        }
        mainapp.dccexSendsStr = mainapp.dccexSendsStr + "</p>";
    }

    // ------------------------------------------

    static class myTimer {
        public boolean timerStarted = false;

        void startTimer() {
            if (!timerStarted) {
                restartTimerInterval();
            }
        }


        //restartInterval()
        void restartTimerInterval() {
            timerStarted = true;
            sendRequestCurrentsMax(); /// only need to call this once
            mainapp.comm_msg_handler.removeCallbacks(currentTimer);                   //remove any pending requests
            mainapp.comm_msg_handler.postDelayed(currentTimer, 3000);    //restart interval
        }

        void stopTimer() {
            mainapp.comm_msg_handler.removeCallbacks(currentTimer);           //remove any pending requests
            timerStarted = false;
            Log.d("EX_Toolbox", "comm_thread.stopTimer: timer stopped.");
        }

        //outboundHeartbeatTimer()
        //sends a periodic message to WiT
        private final Runnable currentTimer = new Runnable() {
            @Override
            public void run() {
                mainapp.comm_msg_handler.removeCallbacks(this);             //remove pending requests
                if (timerStarted) {
                    sendRequestCurrents();
                    timerStarted = true;
                    mainapp.comm_msg_handler.postDelayed(this, 1000);
                }
            }
        };
    }
}

