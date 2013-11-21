package org.sunspotworld; 
  
import com.sun.spot.peripheral.Spot; 
import com.sun.spot.peripheral.TimeoutException; 
import com.sun.spot.peripheral.radio.IRadioPolicyManager; 
import com.sun.spot.peripheral.radio.RadioFactory; 
import com.sun.spot.resources.Resources; 
import com.sun.spot.resources.transducers.ILed; 
import com.sun.spot.resources.transducers.ISwitch; 
import com.sun.spot.resources.transducers.ITriColorLED; 
import com.sun.spot.resources.transducers.LEDColor; 
import com.sun.spot.resources.transducers.ITriColorLEDArray; 
import com.sun.spot.resources.transducers.IAccelerometer3D; 
import com.sun.spot.io.j2me.radiogram.Radiogram; 
import com.sun.spot.io.j2me.radiogram.RadiogramConnection; 
  
import java.util.Stack;
import java.io.IOException; 
import javax.microedition.io.Connector; 
import javax.microedition.io.Datagram; 
import javax.microedition.midlet.MIDlet; 
import javax.microedition.midlet.MIDletStateChangeException; 
  
/* 
instructions: 
LED0: green means receive data from others  
LED1: red means I am the leader 
LED2: blue means get infected 
LED3-6: shows the number of connected spot  
LED7: the tilt color 
  
SW1: if there is no infection, press SW1 will lock the LED7, press again will unlock; 
     if there is an infection, press SW1 will self recover from the infection, press again will again get infected. 
SW2: press SW2 will send signals of infection. Leader have the priority to infected anyone  
     even the leader is infected by others; 
     press again will cancel the infection. 
  
Notice: The Button sometimes not functions well. My suggestion is  connecting the computer while debugging. When you 
        press the button, the screen will shows which button is pressed; 
        There is some delay, and packet lost(which usually happens when infected) 
*/
  
public class Discovery extends MIDlet { 
  
    private static final String VERSION = "1.0"; 
    // CHANNEL_NUMBER  default as 26, each group set their own correspondingly 
    //private static final int CHANNEL_NUMBER = IProprietaryRadio.DEFAULT_CHANNEL;  
    private static final short PAN_ID               = IRadioPolicyManager.DEFAULT_PAN_ID; 
    private static final String BROADCAST_PORT      = "161"; 
    private static final int PACKETS_PER_SECOND     = 1; 
    private static final int PACKET_INTERVAL        = 3000 / PACKETS_PER_SECOND; 
      
    private int channel = 21; 
    private int power = 32;                             // Start with max transmit power 
      
    private ISwitch sw1 = (ISwitch)Resources.lookup(ISwitch.class, "SW1"); 
    private ISwitch sw2 = (ISwitch)Resources.lookup(ISwitch.class, "SW2"); 
    private ITriColorLEDArray leds = (ITriColorLEDArray)Resources.lookup(ITriColorLEDArray.class); 
    private ITriColorLED statusLED = leds.getLED(0); 
    private IAccelerometer3D accel = (IAccelerometer3D)Resources.lookup(IAccelerometer3D.class); 
  
    private LEDColor red   = new LEDColor(50,0,0); 
    private LEDColor green = new LEDColor(0,50,0); 
    private LEDColor blue  = new LEDColor(0,0,50); 
    private LEDColor previous = new LEDColor(0,0,0); 
      
       
      
  
      
    private long myAddr = 0; // own MAC addr (ID) 
    private long leader = 0;  // leader MAC addr  
    private long save_addr[] = {0,0,0,0,0,0};// save all the MAC linked to spot 
    private long TimeStamp;
    private int unique_count = 0;
      
    private double Xtilt;  //Now we send 
    private double Xtilt_Leader = 0;         
    private double Xtilt_Other = 0; 
      
    private int count = 0; 
      
    private boolean xmitDo = true; 
    private boolean recvDo = true; 
    private boolean infection = false;  //true if there is signal sent by Other or leader     
    private boolean tiltchange = false;  //currently not useful 
    private boolean lock_unlock = false;    //indicate if SW1 is on  
    private boolean Leader_infection= false;  //indicate if Leader is sending infection 
    private boolean Other_infection= false;     //indicate if Other is sending infection 
    private boolean Self_recover= false;    //if infected, press SW1 will recover, press again will remain infected 
      
        private void xmitLoop () { 
        RadiogramConnection txConn = null; 
        xmitDo = true; 
        while (xmitDo) { 
            try { 
                txConn = (RadiogramConnection)Connector.open("radiogram://broadcast:" + BROADCAST_PORT); 
                txConn.setMaxBroadcastHops(1);      // don't want packets being rebroadcasted 
                Datagram xdg = txConn.newDatagram(txConn.getMaximumLength()); 
                while (xmitDo) { 
  
                    TimeStamp = System.currentTimeMillis(); 
                    
                    xdg.reset(); 
                    xdg.writeLong(myAddr); // own MAC address 
                    xdg.writeLong(leader); // own leader's MAC address 
                    xdg.writeLong(TimeStamp); // current timestamp 
                    xdg.writeDouble(Xtilt); //local tilt 
                    xdg.writeBoolean(infection); //tiltchange flag if sw1 is pressed  
                    txConn.send(xdg); 
   
                    pause(100);//sending data every 0.1 sec as default 
                          
                }
            }
                catch (IOException ex) { 
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
      
    private void recvLoop () { 
        ILed led = Spot.getInstance().getRedLed(); 
        RadiogramConnection rcvConn = null; 
        recvDo = true; 
        int nothing = 0; 
        long Other_infector = 0; 
        int findLeaderCount =0;
        while (recvDo) { 
            try { 
                rcvConn = (RadiogramConnection)Connector.open("radiogram://:" + BROADCAST_PORT); 
                rcvConn.setTimeout(PACKET_INTERVAL - 5); 
                Radiogram rdg = (Radiogram)rcvConn.newDatagram(rcvConn.getMaximumLength()); 
                  
                while (recvDo) { 
                    System.out.println("LeaderInfection:" +Leader_infection+"   OtherInfection:"+Other_infection+"  LockUnlock:"+lock_unlock+"  SelfRecover:"+Self_recover); 
                    try {   
                        
                            rdg.reset(); 
                            rcvConn.receive(rdg);       // listen for a packet 
                            led.setOn(); 
                            statusLED.setColor(green); 
                            statusLED.setOn(); 
                            long max = 0;
                            long srcAddr = rdg.readLong(); // src MAC address 
                            long srcLeader = rdg.readLong(); // src's leader 
                            long srcTime = rdg.readLong(); // src's timestamp 
                            double srcXtilt = rdg.readDouble(); // src's STEER 
                            boolean srcInfection = rdg.readBoolean(); // src's SPEED 
                            
                            /*1 out of 10 times recalculate the the leader*/
                            if (findLeaderCount == 8)
                            {
                            count = 0;
                            long unique_addr[] = {0,0,0,0,0,0,0,0,0,0};
                            
                            while (count < 10)
                            {
                                
                                rdg.reset(); 
                                rcvConn.receive(rdg);   
                                srcAddr = rdg.readLong(); // src MAC address 
                                srcLeader = rdg.readLong(); // src's leader 
                                srcTime = rdg.readLong(); // src's timestamp 
                                srcXtilt = rdg.readDouble(); // src's STEER 
                                srcInfection = rdg.readBoolean(); // src's SPEED 

                            
                                if (srcAddr > max)
                                {
                                    max = srcAddr;
                                }
                                
                                
                            count++;
                            
                            /*find number of unique addresses*/
                            for (int ii = 0; ii < unique_addr.length; ii++)
                            {
                                if (unique_addr[ii] == srcAddr)
                                {   break;  }
                                else if (unique_addr[ii] == 0)
                                {
                                    unique_addr[ii] = srcAddr;
                                    break;
                                }
                            }
                            }
                            
                            unique_count = 0;
                            for (int ii = 0; ii < unique_addr.length; ii++)
                            {
                                if (unique_addr[ii] != 0)
                                {
                                    unique_count++;
                                }
                            }
                            
                              
                            if (max > myAddr) { 
                                leader = max; 
                            }    
                            else { 
                                leader = myAddr; 
                            } 
                                  
                            if (leader == myAddr) 
                                displayNumber(unique_count, red);  //using 3-6 to display the num connected(leader only) 
                            else { 
                                for(int i=1;i<7;i++){ 
                                    leds.getLED(i).setOff(); 
                                } 
                            }
                                findLeaderCount = 0; //reset find leader count
                            }// end recalculate leader portion                   
                            
                            findLeaderCount++;
                            
                            //if I receive from the leader 
                                if(srcAddr == leader){ 
                                    if (srcInfection == true && srcAddr != myAddr)   
                                    { 
                                        Leader_infection = true; 
                                        Xtilt_Leader = srcXtilt; 
                                        leds.getLED(2).setColor(blue);
                                        leds.getLED(2).setOn();
                                    } 
                                    else 
                                    { 
                                      Leader_infection = false;
                                      leds.getLED(2).setOff();
                                    } 
                                }
                                
                            //else I receive from the Other spot   
                                else 
                                { 
                                    if (srcInfection == true && srcAddr != myAddr) 
                                        { 
                                            Other_infection = true; 
                                            Xtilt_Other = srcXtilt; 
                                            Other_infector = srcAddr;
                                            leds.getLED(2).setColor(blue);
                                            leds.getLED(2).setOn();
                                        }
                                        else
                                        {
                                            Other_infection = false;
                                            leds.getLED(2).setOff();
                                        }
                                          
                                    }    
                                 
                              
                            led.setOff(); 
                              
                    } catch (TimeoutException tex) {        // timeout - display no packet received 
                        statusLED.setColor(red); 
                        statusLED.setOn(); 
                        nothing++; 
                        if (nothing > 2 * PACKETS_PER_SECOND) { 
                            for (int ledint = 0; ledint<=7; ledint++){ // if nothing received eventually turn off LEDs 
                                leds.getLED(ledint).setOff(); 
                            } 
                        } 
                    } 
                } 
            } catch (IOException ex) { 
                // ignore 
            } finally { 
                if (rcvConn != null) { 
                    try { 
                        rcvConn.close(); 
                    } catch (IOException ex) { } 
                } 
            } 
        } 
    } 
      
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
    } 
      
private void LEDLoop() {     
    try { 
    while (true){ 
     
        ILed led = Spot.getInstance().getGreenLed(); 
        led.setOn(); 
        TimeStamp = System.currentTimeMillis(); 
        Xtilt = accel.getTiltX(); 
        if(Leader_infection ==true) { 
            if(myAddr != leader) 
                Xtilt = Xtilt_Leader; 
        } 
        else if(Other_infection ==true) 
        {
            Xtilt = Xtilt_Other; 
        }
        if(lock_unlock == false){ 
  
            if (Xtilt > 0){ 
                leds.getLED(7).setColor(red); 
                previous = red; 
                leds.getLED(7).setOn(); 
                  
            }else if(Xtilt < 0){                 
                leds.getLED(7).setColor(blue); 
                previous = blue; 
                leds.getLED(7).setOn(); 
             } 
        } 
        else { 
            leds.getLED(7).setColor(previous); 
            leds.getLED(7).setOn(); 
        } 
          
    } 
    } 
    catch (IOException ex) { 
                // ignore 
            }  
} 
  
  
    /** 
     * Main application run loop. 
     */
    private void run() { 
  
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
        new Thread() { 
            public void run () { 
                LEDLoop(); 
            } 
        }.start();                      // spawn a thread to control LED 
     // this thread will handle User input via switches 
        respondToSwitches(); 
  
    } 
  
    /*display number in binary*/
    private void displayNumber(int val, LEDColor col) { 
        for (int i = 1, mask = 1; i < 7; i++, mask <<= 1) { 
            leds.getLED(7-i).setColor(col); 
            leds.getLED(7-i).setOn((val & mask) != 0); 
            leds.getLED(1).setColor(col); 
            leds.getLED(1).setOn(); 
        } 
    } 
     
    private void respondToSwitches() { 
        while (true) { 
            pause(50);         // check every 0.1 seconds 
              
            if (sw1.isClosed()) { 
                System.out.println("B1 is pressing");
                
                for (int ii = 0; ii < 8; ii++)
                {
                    leds.getLED(ii).setColor(new LEDColor(25,25,25));
                }
                if(Leader_infection||Other_infection) 
                {   
                    Leader_infection = false;
                    Other_infection = false;
                }
                else
                {
                lock_unlock = !lock_unlock; 
                pause(150); 
                }
            } 
            if (sw2.isClosed()) { 
                System.out.println("B2 is pressing"); 
                
                for (int ii = 0; ii < 8; ii++)
                {
                    leds.getLED(ii).setColor(new LEDColor(25,25,25));
                }
                if(Leader_infection ==false) 
                    infection = !infection; 
                if (myAddr == leader ) 
                    Leader_infection = !Leader_infection; 
                pause(150); 
            } 
  
              
        } 
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