package de.vodafone.innogarage.sfcdtool;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.github.aakira.expandablelayout.ExpandableRelativeLayout;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity implements
        OnMapReadyCallback,
        GoogleMap.OnMyLocationButtonClickListener,
        ActivityCompat.OnRequestPermissionsResultCallback {
    public volatile boolean serverOn = true;

    /*--------Connection Variables------------*/
    public static int socketPortForBroadcast = 45555;
    public static int socketServerPortForSFCD = 45556;
    public int bufferSize = 4096;
    public ConnectionManager conMan = new ConnectionManager();
    public Context globalContext;

    /*--------Additional Variables------------*/
    TimerTask timerTask;
    TimerTask timerTask2;
    Timer timer2 = new Timer();
    Timer timer = new Timer();



    /*--------Layout Variables----------------*/
    ExpandableRelativeLayout expandableLayout1, expandableLayout2, expandableLayout3, expandableLayout4, expandableLayout5,expandableLayout6;
    private TableLayout tableGstatus;
    private TableLayout tableLocation;
    private TableLayout tableServing;
    private TableLayout tableInterfreq;
    private TableLayout tableIntrafreq;
    private Button buttonState;



    /*--------Connections List----------------*/
    public volatile List<Connection> connectionList;
    private ListView listDevices;
    private ListViewAdapter listAdapter;


    //------------Map Var---------------------------------------
    private GoogleMap mMap;

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private boolean mPermissionDenied = false;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        globalContext = this;
        //Define table layouts
        tableGstatus = (TableLayout) findViewById(R.id.gTable);
        tableLocation = (TableLayout) findViewById(R.id.locTable);
        tableServing = (TableLayout) findViewById(R.id.sTable);
        tableInterfreq = (TableLayout) findViewById(R.id.iteTable);
        tableIntrafreq = (TableLayout) findViewById(R.id.itaTable);
        buttonState = (Button) findViewById(R.id.buttonState);


        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync((OnMapReadyCallback) this);

        connectionList = new CopyOnWriteArrayList<Connection>();
        listDevices = (ListView) findViewById(R.id.mSFCDList);
        listAdapter = new ListViewAdapter(globalContext, connectionList);
        listDevices.setAdapter(listAdapter);











//Cuando se presione el botón para invitar.

        timerTask2 = new TimerTask() {
            @Override
            public void run() {
                for (Connection connec : connectionList){
                    if(connec.isClose()){
                        connectionList.remove(connec);
                        listAdapter.notifyDataSetChanged();


                    }
                }





            }
        };
        timer2.schedule(timerTask2, 0, 500);



        timerTask = new TimerTask() {
            @Override
            public void run() {

                try {
                    new DisplayResults().execute();
                    Thread.sleep(10);

                }
                catch (Exception ex){
                    ex.printStackTrace();
                    Log.e("TimerScanner","Error executing ScannerTask");

                }

            }
        };
        timer.schedule(timerTask, 0, 200);





    }




    /*=============================Connection Manager===================================*/
    //Creates Server socket and binds to a socket object for every new connection accepted

    public class ConnectionManager {
        private ServerSocket serverSocket;

        public ConnectionManager() {

            //Creating Server Socket
            try{
                serverSocket = new ServerSocket(socketServerPortForSFCD);
            }catch (IOException e){
                Log.e("ConnectionManager","Unable to create Server socket");
            }
            new ConnectionListenerForSFCD().start();


        }


        private class ConnectionListenerForSFCD extends Thread{
            @Override
            public void run() {
                while (serverOn){
                    Socket clientSocket = null;
                    try{
                        clientSocket = serverSocket.accept();
                        Log.e("ConnectionManager "," Connection Listener :Waiting for connections!\n");
                        connectionList.add(new Connection(clientSocket));
                        Log.e("ConnectionManager "," Connection Listener: New SFCD added. IP= " + clientSocket.getInetAddress().toString());
                        //listAdapter.notifyDataSetChanged();

                        //Connections List changed. Notify ListView adapter
                    }catch (IOException e){
                        e.printStackTrace();
                        Log.e("ConnectionManager","Could not accept client request");
                    }
                }
            }
        }



        public void setServerState (boolean serverState){
            serverOn = serverState;

        }

        public void clearConnections() throws IOException {
            if (!connectionList.isEmpty()){
                for (Connection connect : connectionList){
                    connect.cliSocket.close();

                }
                connectionList.clear();
            }

        }

        public void sendInvitation(){
            new Broadcaster().start();
        }





    }

    /*===================================== Connection =======================================*/
    //Starts reception of messages
    public class Connection {
        private Socket cliSocket;
        private int errorCounter = 0;
        private boolean close,focus, online;
        private List<JSONObject> incomingData;
        public volatile JSONObject incomingJson;
        private InputStream inputStream;
        //Client Data
        private String clientName;
        private String clientIP;
        private String mode;
        //public Double latitude;
        //public Double longitude;



        private Connection(Socket cliSocket){
            this.cliSocket = cliSocket;
            clientName = cliSocket.getInetAddress().getHostName();
            clientIP = cliSocket.getInetAddress().toString();
            focus = false;
            close = false;
            online=false;

            //Get incoming stream and place it in a List
            try{
                inputStream = cliSocket.getInputStream();
            }catch (IOException e){
                e.printStackTrace();
                Log.e("Connection","Could not get incoming stream");
            }
            new InputStreamThread().start();
        }

        private class InputStreamThread extends Thread {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

            public void run(){
                JSONObject jsonObject;
                String line = null;

                while (!close) {
                    try {
                        line = bufferedReader.readLine();
                        Log.e("breader","LINE :"+ line);
                    } catch (IOException e) {
                        Log.e("readLine", "Could not read line, following error happened: ");
                        e.printStackTrace();
                    }

                    if (line == null) {
                        //Many errors trying to read line. Assuming connection with SFCD is over
                        errorCounter = errorCounter + 1;
                        if (errorCounter > 5) {
                            Log.e("\nInputStreamThread", "SFCD " + clientName + " seems to be inactive. Stopping receiving thread...\n");
                            close = true;
                            // TODO: Implement method to remove this connection.
                           // connectionList.remove(Connection);

                            try {
                                cliSocket.close();
                                cliSocket = null;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            Log.e("\nInputStreamThread", "SFCD " + clientName + " Connection removed");
                        }
                    }
                    //Save incoming message into a json object
                    else {
                        try {
                            line = line.substring(line.indexOf("{") );
                            Log.e("Buffer after conv","LINE :"+ line);
                            //System.out.print("Converted line: "+line);

                            jsonObject = new JSONObject(line);

                        } catch (JSONException e) {
                            e.printStackTrace();
                            Log.e("InputStreamThread: ", cliSocket.getInetAddress() + "   Could not save Json Object with incoming stream");
                            jsonObject = new JSONObject();
                        }
                        //Save into list of incoming data objects
                        incomingJson = jsonObject;
                       // incomingData.add(jsonObject);
                        Log.e("Connection: ", cliSocket.getInetAddress() + " Message received: " + jsonObject.toString() + " => Placed in incomingData, parsed as JSON");
                        //Check if Mode is online or not
                        try {
                            mode = jsonObject.getJSONObject("gstatus").getString("mode");
                            if (mode.equalsIgnoreCase("ONLINE")) {

                                online = true;

                            } else {

                                online = false;

                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                            System.out.print("Could not save Mode value");
                        }

                        try {
                            Double latitude = jsonObject.getJSONObject("gstatus").getDouble("latitude");
                            Double longitude = jsonObject.getJSONObject("gstatus").getDouble("longitude");
                            int snr = jsonObject.getJSONArray("serving").getJSONObject(0).getInt("SNR");
                            displayMarker(latitude, longitude, snr, 1.0F);

                        }
                        catch (JSONException e){
                            System.out.print("Could not get coordinates data:");
                            e.printStackTrace();




                        }
                    }

                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public String getName() {return clientName;}
        public String getIP(){return clientIP;}
        public List<JSONObject> getIncomingData(){return incomingData;}
        public JSONObject getIncomingJson(){return incomingJson;}
        //public Double getlongitude(){return longitude;}
        //public Double getlatitude(){return latitude;}
        public boolean isFocus() {return focus;}
        public void setFocus(boolean focus) {this.focus = focus;}
        public boolean isOnline() {return online;}
        public boolean isClose(){return close;}

    }



    /*=====================PRINTING RESULTS ON SCREEN=================*/

    public class DisplayResults extends AsyncTask<Void,Void,JSONObject>{


        @Override
            protected JSONObject doInBackground(Void... params) {
            JSONObject actMsg = null;
            Connection con = null;
            if(!connectionList.isEmpty()){
                for (Connection temp : connectionList){
                    if(temp.isFocus()){
                        con = temp;
                    }
                }
                if (con == null){
                    con = connectionList.get(0);
                }

                actMsg = con.getIncomingJson();
            }

            return actMsg;
        }


        @Override
        protected void onPostExecute(JSONObject result) {
            double lat = 0;
            double longi = 0;
            int snr = 0;


            if(!connectionList.isEmpty()){
                if(result!=null){

                    Iterator<?> keys = result.keys();
                    TextView temp;


                    while (keys.hasNext()) {
                        String mKey = (String) keys.next();
                        switch (mKey) {
                            case "gstatus":
                                try {


                                    JSONObject gobj = result.getJSONObject("gstatus");

                                    if (gobj != null) {
                                        temp = (TextView) findViewById(R.id.ltebw);
                                        temp.setText(gobj.getString("ltebw(mhz)"));
                                        temp = (TextView) findViewById(R.id.rsrprxdr);
                                        temp.setText(gobj.getString("rsrp(dbm)pccrxdrssi"));
                                        temp = (TextView) findViewById(R.id.rsrprxm);
                                        temp.setText(gobj.getString("rsrp(dbm)pccrxmrssi"));
                                        temp = (TextView) findViewById(R.id.grsrq);
                                        temp.setText(gobj.getString("rsrq(db)"));
                                        temp = (TextView) findViewById(R.id.gsinr);
                                        temp.setText(gobj.getString("sinr(db)"));
                                        temp = (TextView) findViewById(R.id.gmode);
                                        temp.setText(gobj.getString("mode"));
                                        temp = (TextView) findViewById(R.id.ltecastate);
                                        temp.setText(gobj.getString("ltecastate"));
                                        temp = (TextView) findViewById(R.id.cellid);
                                        temp.setText(gobj.getString("cellid"));
                                        temp = (TextView) findViewById(R.id.currenttime);
                                        temp.setText(gobj.getString("currenttime"));
                                        temp = (TextView) findViewById(R.id.ltetxchan);
                                        temp.setText(gobj.getString("ltetxchan"));
                                        temp = (TextView) findViewById(R.id.gtac);
                                        temp.setText(gobj.getString("tac"));
                                        temp = (TextView) findViewById(R.id.emmstatereg);
                                        temp.setText(gobj.getString("emmstatereg"));
                                        temp = (TextView) findViewById(R.id.rrcstate);
                                        temp.setText(gobj.getString("rrcstate"));
                                        temp = (TextView) findViewById(R.id.temperature);
                                        temp.setText(gobj.getString("temperature"));
                                        temp = (TextView) findViewById(R.id.systemmode);
                                        temp.setText(gobj.getString("systemmode"));
                                        temp = (TextView) findViewById(R.id.psstate);
                                        temp.setText(gobj.getString("psstate"));
                                        temp = (TextView) findViewById(R.id.emmstateserv);
                                        temp.setText(gobj.getString("emmstateserv"));
                                        temp = (TextView) findViewById(R.id.lteband);
                                        temp.setText(gobj.getString("lteband"));
                                        temp = (TextView) findViewById(R.id.lterxchan);
                                        temp.setText(gobj.getString("lterxchan"));
                                        temp = (TextView) findViewById(R.id.gtxpower);
                                        temp.setText(gobj.getString("txpower"));
                                        temp = (TextView) findViewById(R.id.imsregstate);
                                        temp.setText(gobj.getString("imsregstate"));
                                        temp = (TextView) findViewById(R.id.resetcounter);
                                        temp.setText(gobj.getString("resetcounter"));
                                        temp = (TextView) findViewById(R.id.pccrxdrssi);
                                        temp.setText(gobj.getString("pccrxdrssi"));
                                        temp = (TextView) findViewById(R.id.pccrxrmrssi);
                                        temp.setText(gobj.getString("pccrxmrssi"));

                                    }
                                } catch (JSONException e) {
                                    System.out.println("Could not extract gstatus object -->");
                                    e.printStackTrace();

                                }
                                break;


                            case "serving":
                                try {
                                    JSONArray sarr = result.getJSONArray("serving");
                                    int j = 0;
                                    for (int i = 0; i < sarr.length(); i++) {
                                        JSONObject sobj = sarr.getJSONObject(i);
                                        Iterator<?> skeys = sobj.keys();
                                        while (skeys.hasNext()) {
                                            String sKey = (String) skeys.next();
                                            TableRow row = new TableRow(globalContext);
                                            TableRow.LayoutParams lp = new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT);
                                            row.setLayoutParams(lp);
                                            TextView key = new TextView(globalContext);
                                            TextView value = new TextView(globalContext);
                                            key.setText(sKey.toUpperCase());
                                            value.setText(sobj.getString(sKey));
                                            row.setBackground(getResources().getDrawable(R.drawable.cell_shape));
                                            row.addView(key);
                                            row.addView(value);
                                            tableServing.addView(row, j);
                                            j = j + 1;
                                        }
                                    }
                                } catch (JSONException e) {
                                    System.out.println("Could not extract serving object -->");
                                    e.printStackTrace();
                                }
                                break;


                            case "interfreq":
                                try {
                                    JSONArray itearr = result.getJSONArray("interfreq");
                                    int j = 0;
                                    for (int i = 0; i < itearr.length(); i++) {
                                        JSONObject sobj = itearr.getJSONObject(i);
                                        Iterator<?> skeys = sobj.keys();
                                        while (skeys.hasNext()) {
                                            String sKey = (String) skeys.next();
                                            TableRow row = new TableRow(globalContext);
                                            TableRow.LayoutParams lp = new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT);
                                            row.setLayoutParams(lp);
                                            TextView key = new TextView(globalContext);
                                            TextView value = new TextView(globalContext);
                                            key.setText(sKey.toUpperCase());
                                            value.setText(sobj.getString(sKey));
                                            row.setBackground(getResources().getDrawable(R.drawable.cell_shape));
                                            row.addView(key);
                                            row.addView(value);
                                            tableInterfreq.addView(row, j);
                                            j = j + 1;
                                        }
                                    }


                                } catch (JSONException e) {
                                    System.out.println("Could not extract interfreq object -->");
                                    //   e.printStackTrace();
                                }
                                break;
                            case "intrafreq":
                                try {
                                    JSONArray itaarr = result.getJSONArray("intrafreq");
                                    int j = 0;
                                    for (int i = 0; i < itaarr.length(); i++) {
                                        JSONObject sobj = itaarr.getJSONObject(i);
                                        Iterator<?> skeys = sobj.keys();
                                        while (skeys.hasNext()) {
                                            String sKey = (String) skeys.next();
                                            TableRow row = new TableRow(globalContext);
                                            TableRow.LayoutParams lp = new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT);
                                            row.setLayoutParams(lp);
                                            TextView key = new TextView(globalContext);
                                            TextView value = new TextView(globalContext);
                                            key.setText(sKey.toUpperCase());
                                            value.setText(sobj.getString(sKey));
                                            row.setBackground(getResources().getDrawable(R.drawable.cell_shape));
                                            row.addView(key);
                                            row.addView(value);
                                            tableIntrafreq.addView(row, j);
                                            j = j + 1;
                                        }
                                    }
                                } catch (JSONException e) {
                                    System.out.println("Could not extract intrafreq object -->");
                                    e.printStackTrace();
                                }
                                break;

                            case "location":
                                try {
                                    JSONObject lobj = result.getJSONObject("location");
                                    if (lobj != null) {

                                        temp = (TextView) findViewById(R.id.altitude);
                                        temp.setText(lobj.getString("altitude"));
                                        temp = (TextView) findViewById(R.id.ept);
                                        temp.setText(lobj.getString("ept"));
                                        temp = (TextView) findViewById(R.id.climb);
                                        temp.setText(lobj.getString("climb"));
                                        temp = (TextView) findViewById(R.id.eps);
                                        temp.setText(lobj.getString("eps"));
                                        temp = (TextView) findViewById(R.id.epv);
                                        temp.setText(lobj.getString("epv"));
                                        temp = (TextView) findViewById(R.id.epx);
                                        temp.setText(lobj.getString("epx"));
                                        temp = (TextView) findViewById(R.id.speed);
                                        temp.setText(lobj.getString("speed"));
                                        temp = (TextView) findViewById(R.id.track);
                                        temp.setText(lobj.getString("track"));
                                        temp = (TextView) findViewById(R.id.longitude);
                                        temp.setText(lobj.getString("longitude"));
                                        longi = lobj.getDouble("longitude");
                                        temp = (TextView) findViewById(R.id.latitude);
                                        temp.setText(lobj.getString("latitude"));
                                        lat = lobj.getDouble("latitude");
                                        temp = (TextView) findViewById(R.id.satellites);
                                        temp.setText(lobj.getString("satellites"));
                                        temp = (TextView) findViewById(R.id.mode);
                                        temp.setText(lobj.getString("mode"));


                                    }
                                } catch (JSONException e) {
                                    System.out.println("Could not extract location object -->");
                                    e.printStackTrace();
                                    break;
                                }

                                break;
                        }

                    }
                }

                else{
                    clearView();
                }
            }
            else {
                clearView();
                clearButton();

            }
        }
    }



    /*=========================LAYOUT MODIFIERS=====================*/

    /*-------------------- Display a Marker on the Map------------------------*/
    public void displayMarker(final Double Lati, final Double Longi, int snr, final float  alphaValue){
        final long TimeToLive = 2000;
        final BitmapDescriptor myicon;
        int id = 0;
        if (snr <= 10){
            id = getResources().getIdentifier("red", "drawable", getPackageName());
        }
        else if(snr >= 20){
            id = getResources().getIdentifier("yellow", "drawable", getPackageName());
        }
        else{
            id = getResources().getIdentifier("green", "drawable", getPackageName());
        }

        myicon = BitmapDescriptorFactory.fromResource(id);

        runOnUiThread(new Runnable(){
            @Override
            public void run(){
                LatLng pos = new LatLng(Lati,Longi);
               /* Marker mar = mMap.addMarker(new MarkerOptions()
                        .position(pos)
                        .icon(myicon)
                        .alpha(alphaValue)
                );
                fadeTime(TimeToLive,mar);*/
            }

        });
    }

    /*-----Customize characteristics of the markers: time to fade--------*/


    public void fadeTime(long duration, Marker marker) {

        final Marker myMarker = marker;
        ValueAnimator myAnim = ValueAnimator.ofFloat(1, 0);
        myAnim.setDuration(duration);
        myAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                myMarker.setAlpha((float) animation.getAnimatedValue());
            }
        });
        myAnim.start();
    }


    /*------------Methods for displaying the data on the list----------------*/
    public void clearView(){
        TextView temp;
        temp = (TextView) findViewById(R.id.ltebw);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.rsrprxdr);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.rsrprxm);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.grsrq);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.gsinr);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.gmode);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.ltecastate);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.cellid);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.currenttime);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.ltetxchan);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.gtac);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.emmstatereg);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.rrcstate);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.temperature);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.systemmode);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.psstate);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.emmstateserv);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.lteband);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.lterxchan);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.gtxpower);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.imsregstate);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.resetcounter);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.pccrxdrssi);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.pccrxrmrssi);
        temp = (TextView) findViewById(R.id.altitude);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.ept);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.climb);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.eps);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.epv);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.epx);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.speed);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.track);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.longitude);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.latitude);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.satellites);
        temp.setText("N/A");
        temp = (TextView) findViewById(R.id.mode);
        temp.setText("N/A");
        //tableGstatus.removeAllViews();
        //tableLocation.removeAllViews();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tableServing.removeAllViews();
                tableInterfreq.removeAllViews();
                tableIntrafreq.removeAllViews();
            }
        });

    }

    public void clearButton(){

        buttonState.setVisibility(View.INVISIBLE);

    }


    /*------------------List of devices adapter------------------------------*/
    public class ListViewAdapter extends BaseAdapter {

        List<Connection> connectionList;
        Context context;
        LayoutInflater inflater;

        public ListViewAdapter(Context c,List<Connection> connectionList) {
            this.context = c;
            this.connectionList = connectionList;
            inflater = (LayoutInflater.from(c));
        }



        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            convertView = inflater.inflate(R.layout.clients_list, parent, false);
            TextView tv_hostname = (TextView) convertView.findViewById(R.id.client_name);
            TextView tv_clientIP = (TextView) convertView.findViewById(R.id.client_ip);
            final Button button = (Button) convertView.findViewById(R.id.detailsButton);
            button.setBackgroundColor(Color.GRAY);
            button.setText("--");

            //Button buttonState= (Button) findViewById(R.id.buttonState);

            final Connection con = connectionList.get(position);


            button.setOnClickListener(new View.OnClickListener(){

                public void onClick(View v){
                    button.setBackgroundColor(getResources().getColor(R.color.lightGreen));
                    button.setText("Selected");


                    for(Connection conn : connectionList){

                        conn.setFocus(false);


                    }
                    con.setFocus(true);
                    if(con.isOnline()){

                        buttonState.setVisibility(View.VISIBLE);
                        buttonState.setBackgroundColor(getResources().getColor(R.color.lightGreen));
                        buttonState.setText("ONLINE: "+con.getName());

                    }else{

                        buttonState.setVisibility(View.VISIBLE);
                        buttonState.setBackgroundColor(getResources().getColor(R.color.red));
                        buttonState.setText("OFFLINE: "+con.getName());
                    }
                    //clearView();
                }
            });

            tv_hostname.setText(con.getName());
            tv_clientIP.setText(con.getIP());

            return convertView;
        }



        @Override
        public int getCount() {
            return connectionList.size() ;
        }

        @Override
        public Object getItem(int position) {
            return connectionList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

    }










    /*------------------Expandable List View adapter -----------------------*/
    public void expandableButton1(View view) {
        expandableLayout1 = (ExpandableRelativeLayout) findViewById(R.id.expandableLayout1);
        expandableLayout1.toggle(); // toggle expand and collapse
    }

    public void expandableButton2(View view) {
        expandableLayout2 = (ExpandableRelativeLayout) findViewById(R.id.expandableLayout2);
        expandableLayout2.toggle(); // toggle expand and collapse
    }

    public void expandableButton3(View view) {
        expandableLayout3 = (ExpandableRelativeLayout) findViewById(R.id.expandableLayout3);
        expandableLayout3.toggle(); // toggle expand and collapse
    }

    public void expandableButton4(View view) {
        expandableLayout4 = (ExpandableRelativeLayout) findViewById(R.id.expandableLayout4);
        expandableLayout4.toggle(); // toggle expand and collapse
    }

    public void expandableButton5(View view) {
        expandableLayout5 = (ExpandableRelativeLayout) findViewById(R.id.expandableLayout5);
        expandableLayout5.toggle(); // toggle expand and collapse
    }
    public void expandableButton6(View view) {
        expandableLayout6 = (ExpandableRelativeLayout) findViewById(R.id.expandableLayout6);
        expandableLayout6.toggle(); // toggle expand and collapse
    }

    /*-----------------Menu on Toolbar------------------------------------*/
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_invite:
                serverOn = true;
                try {
                    conMan.clearConnections();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                conMan.sendInvitation();

                Toast.makeText(MainActivity.this,"Invitation sent",Toast.LENGTH_LONG).show();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }



    //-------------------------------------------------------------------------------------------------------------------------------------------------------------
//-----------------------------------------------------------------Map Code------------------------------------------------------------------------------------
//-------------------------------------------------------------------------------------------------------------------------------------------------------------
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        Log.d("MapReady", "map is ready");
        // Add a marker in Sydney and move the camera
        mMap.setOnMyLocationButtonClickListener(this);
        enableMyLocation();
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);

    }
    //----------------------------------------------------------------------------My current location-----------------------------------------------------------
    private void enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission to access the location is missing.
            PermissionUtils.requestPermission(this, LOCATION_PERMISSION_REQUEST_CODE,
                    Manifest.permission.ACCESS_FINE_LOCATION, true);
        } else if (mMap != null) {
            // Access to the location has been granted to the app.
            mMap.setMyLocationEnabled(true);
        }
    }



    @Override
    public boolean onMyLocationButtonClick() {
        Toast.makeText(this, "MyLocation button clicked", Toast.LENGTH_SHORT).show();
        // Return false so that we don't consume the event and the default behavior still occurs
        // (the camera animates to the user's current position).
        return false;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) {
            return;
        }

        if (PermissionUtils.isPermissionGranted(permissions, grantResults,
                Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Enable the my location layer if the permission has been granted.
            enableMyLocation();
        } else {
            // Display the missing permission error dialog when the fragments resume.
            mPermissionDenied = true;
        }
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();
        if (mPermissionDenied) {
            // Permission was not granted, display error dialog.
            showMissingPermissionError();
            mPermissionDenied = false;
        }
    }

    /**
     * Displays a dialog with error message explaining that the location permission is missing.
     */
    private void showMissingPermissionError() {
        PermissionUtils.PermissionDeniedDialog
                .newInstance(true).show(getSupportFragmentManager(), "dialog");
    }


    //---------------------------------------------------------------------------- End my Location-----------------------------------------------------------










}









