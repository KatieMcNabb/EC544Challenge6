/*
 * SunSpotApplication.java
 *
 * Created on Nov 15, 2012 1:44:50 AM;
 */

package org.sunspotworld;

import com.sun.spot.peripheral.Spot;
import com.sun.spot.peripheral.TimeoutException;
import com.sun.spot.peripheral.radio.IProprietaryRadio;
import com.sun.spot.peripheral.radio.IRadioPolicyManager;
import com.sun.spot.peripheral.radio.RadioFactory;
import com.sun.spot.resources.Resources;
import com.sun.spot.resources.transducers.ILed;
import com.sun.spot.resources.transducers.ISwitch;
import com.sun.spot.resources.transducers.ITriColorLED;
import com.sun.spot.resources.transducers.LEDColor;
import com.sun.spot.resources.transducers.ITriColorLEDArray;
import com.sun.spot.io.j2me.radiogram.Radiogram;
import com.sun.spot.io.j2me.radiogram.RadiogramConnection;

import java.io.IOException;
import javax.microedition.io.Connector;
import javax.microedition.io.Datagram;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**

 */
public class Discovery extends MIDlet {

    private static final String VERSION = "1.0";
    // CHANNEL_NUMBER  default as 26, each group set their own correspondingly
    private static final int CHANNEL_NUMBER =IProprietaryRadio.DEFAULT_CHANNEL;
    private static final short PAN_ID               = 12;//IRadioPolicyManager.DEFAULT_PAN_ID;
    private static final String BROADCAST_PORT      = "65";
    private static final int PACKETS_PER_SECOND     = 1;
    private static final int PACKET_INTERVAL        = 3000 / PACKETS_PER_SECOND;
 //   private static AODVManager aodv = AODVManager.getInstance();
    
    private int channel = CHANNEL_NUMBER;
    private int power = 32;                             // Start with max transmit power
    
    private ITriColorLEDArray leds = (ITriColorLEDArray)Resources.lookup(ITriColorLEDArray.class);
    private ITriColorLED statusLED = leds.getLED(0);

    private LEDColor red = new LEDColor(0,0,50);
    
    private boolean xmitDo = true;
    private boolean recvDo = true;
    
    private long myAddr = 0; // own MAC addr (ID)
    private long leader = 0;  // leader MAC addr 
    private boolean leaderSet = false;
    private long TimeStamp;
   
   
     /**
     * Loop to continually broadcast message.
     * message format
     */
        private void xmitLoop () {
        
        RadiogramConnection txConn = null;
        xmitDo = true;
        while (xmitDo) {
            
        
            try {
                txConn = (RadiogramConnection)Connector.open("radiogram://broadcast:" + BROADCAST_PORT);
                txConn.setMaxBroadcastHops(1);      // don't want packets being rebroadcasted
                Datagram xdg = txConn.newDatagram(txConn.getMaximumLength());
                while (xmitDo) {
                    
                    /*pause*/
                    long delay = (TimeStamp+ PACKET_INTERVAL- System.currentTimeMillis()) - 2;
                    if (delay > 0) {
                        pause(delay);
                    }
                }
            } catch (IOException ex) {
                // ignore
            } finally {
                if (txConn != null) {
                    try {
                        txConn.close();
                    } catch (IOException ex) { }
                }
            }
        }
    }
    
    /**
     * Loop to receive packets and discover peers information 
     */
    private void recvLoop () {
        RadiogramConnection rcvConn = null;
        recvDo = true;
        int count = 0;
        
        while (recvDo) {
            try {
                rcvConn = (RadiogramConnection)Connector.open("radiogram://:" + BROADCAST_PORT);
                rcvConn.setTimeout(PACKET_INTERVAL - 5);
                Radiogram rdg = (Radiogram)rcvConn.newDatagram(rcvConn.getMaximumLength());
                
                /*for 9 out of 10 loops we look for leader's message
                 * on 10th loop look for a new leader
                 */
                while (count < 8) {
                    try {
                        rdg.reset();
                        rcvConn.receive(rdg);           // listen for a packet
                        
                        /*we have a known leader*/
                        if (leaderSet == true)
                        {
                            /*we found the leaders message*/
                            if (rdg.readLong() == leader)
                            {
                                rdg.readBoolean(); //read whether they should lock to leader's position
                            }
                        }
                        count++;
                            
                    } catch (TimeoutException tex) {        // timeout - display no packet received
                        statusLED.setColor(red);
                    }
                }
                findLeader(rcvConn, rdg);
                count = 0;
            } catch (IOException ex) {
                
            } finally {
                if (rcvConn != null) {
                    try {
                        rcvConn.close();
                    } catch (IOException ex) { }
                }
            }
        }
    }
    
    /*look for leader helper function
     * looks through 10 messages and finds the highest mac address
     * assumes this is the leader
     * if no mac address is higher than it's own it is the leader
     */
    private void findLeader(RadiogramConnection rConn, Radiogram rdg) throws IOException
    {
        int count = 0;
        long max = 0;
        
        while (count < 10)
        {
            rdg.reset();
            rConn.receive(rdg);
            long receivedAddress = rdg.readLong();
            boolean receivedBool = rdg.readBoolean();
            if (receivedAddress > max)
            {
                max = receivedAddress;
            }
            
            count++;
        }
        
        if (max > myAddr)
        {
            leaderSet = true;
            leader = max;
        }
        else
        {
            /*I am the leader*/
            leaderSet = false;
        }
    }
        
    
    
  
    /**
     * Pause for a specified time.
     *
     * @param time the number of milliseconds to pause
     */
    private void pause (long time) {
        try {
            Thread.currentThread().sleep(time);
        } catch (InterruptedException ex) { /* ignore */ }
    }
    

    /**
     * Initialize any needed variables.
     */
    private void initialize() { 
        myAddr = RadioFactory.getRadioPolicyManager().getIEEEAddress();
        statusLED.setColor(red);     // Red = not active
        statusLED.setOn();
        IRadioPolicyManager rpm = Spot.getInstance().getRadioPolicyManager();
        rpm.setChannelNumber(channel);
        rpm.setPanId(PAN_ID);
        rpm.setOutputPower(power - 32);
    //    AODVManager rp = Spot.getInstance().
    }
    

    /**
     * Main application run loop.
     */
    private void run() {
        System.out.println("Radio Signal Strength Test (version " + VERSION + ")");
        System.out.println("Packet interval = " + PACKET_INTERVAL + " msec");
        
        new Thread() {
            public void run () {
                xmitLoop();
            }
        }.start();                      // spawn a thread to transmit packets
        new Thread() {
            public void run () {
                recvLoop();
            }
        }.start();                      // spawn a thread to receive packets
    }
       
    /**
     * MIDlet call to start our application.
     */
    protected void startApp() throws MIDletStateChangeException {
	// Listen for downloads/commands over USB connection
	new com.sun.spot.service.BootloaderListenerService().getInstance().start();
        initialize();
        run();
    }

    /**
     * This will never be called by the Squawk VM.
     */
    protected void pauseApp() {
        // This will never be called by the Squawk VM
    }

    /**
     * Called if the MIDlet is terminated by the system.
     * @param unconditional If true the MIDlet must cleanup and release all resources.
     */
    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
    }

}