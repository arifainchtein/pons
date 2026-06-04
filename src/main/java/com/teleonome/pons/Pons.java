package com.teleonome.pons;


import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.io.gpio.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;

/**
 * PONS: The Bridge Organ
 * Manages RS485 timing and routes packets to the RAM Disk for the Hypothalamus.
 */
public class Pons {
    // GPIO 18 (Physical Pin 12) for DE/RE control
    private static final Pin PIN_DE_RE = RaspiPin.GPIO_01; 
    private static final String SERIAL_PORT = "/dev/ttyS0";
    
    // RAM Disk paths (tmpfs) to save SD card life
    private static final String RAM_DISK = "/home/ari/sensors/";
    private static final String OUTBOX_FILE = "/home/ari/teleonome_outbox.txt";
    private static final String THALAMUS_PULSE = "/home/ari/thalamus/pons_pulse.json";

    public static void main(String[] args) throws Exception {
        long pid = ProcessHandle.current().pid();
        
        // 1. Initialize Hardware Control
        final GpioController gpio = GpioFactory.getInstance();
        final GpioPinDigitalOutput deRePin = gpio.provisionDigitalOutputPin(PIN_DE_RE, "Pons_Reflex", PinState.LOW);
        
        // 2. Initialize Serial Port
        com.fazecast.jSerialComm.SerialPort comPort = SerialPort.getCommPort(SERIAL_PORT);
        comPort.setBaudRate(9600); // Match your ESP32 Master
        comPort.openPort();

        System.out.println("Pons Organ Active (PID: " + pid + "). Monitoring RS485 Bus...");

        try {
            StringBuilder packetBuffer = new StringBuilder();
            
            while (true) {
                // Default: Listener Mode (DE/RE LOW)
                deRePin.low(); 

                if (comPort.bytesAvailable() > 0) {
                    byte[] readBuffer = new byte[comPort.bytesAvailable()];
                    comPort.readBytes(readBuffer, readBuffer.length);
                    String fragment = new String(readBuffer);
                    packetBuffer.append(fragment);

                    // Check if we have a full packet (assuming newline termination)
                    if (packetBuffer.toString().contains("\n")) {
                        String fullPacket = packetBuffer.toString().trim();
                        packetBuffer.setLength(0); // Clear buffer

                        // --- ROUTING LOGIC ---
                        // Expected format: SENDER_ID|DATA (e.g., ESP32SLAVE1|23.5#55#1010)
                        if (fullPacket.contains("|")) {
                            String[] parts = fullPacket.split("\\|");
                            String senderId = parts[0].toLowerCase();
                            String data = parts[1];

                            // Write to RAM Disk for Hypothalamus
                            String sensorPath = RAM_DISK + senderId + ".json";
                            JSONObject dataObj = new JSONObject();
                            dataObj.put("id", senderId);
                            dataObj.put("payload", data);
                            dataObj.put("ts", System.currentTimeMillis());
                            
                            Files.writeString(Paths.get(sensorPath), dataObj.toString());
                        }

                        // --- INTERRUPT LOGIC ---
                        // Check if Master ESP32 is asking the Pi for status
                        if (fullPacket.contains("?PI_STATUS")) {
                            respondToMaster(deRePin, comPort);
                        }
                    }
                    
                    // Pulse the Thalamus to show we are perceiving data
                    updateThalamus(pid, "Listening");
                }
                Thread.sleep(10); // High frequency for bus responsiveness
            }
        } finally {
            comPort.closePort();
            gpio.shutdown();
        }
    }

    private static void respondToMaster(GpioPinDigitalOutput deRePin, com.fazecast.jSerialComm.SerialPort comPort) throws Exception {
        deRePin.high(); // Switch to Talk Mode
        Thread.sleep(2); // Stabilization delay

        File outbox = new File(OUTBOX_FILE);
        String response = outbox.exists() ? Files.readString(outbox.toPath()) : "PI_ALIVE";
        
        byte[] responseBytes = (response + "\n").getBytes();
        comPort.writeBytes(responseBytes, responseBytes.length);

        // Wait for the TX buffer to clear before releasing the bus
     // 1. Send the data
        int bytesSent = comPort.writeBytes(responseBytes, responseBytes.length);

        // 2. Calculate a safe "Guard Time" (10 bits per byte / 9600 baud * 1000ms)
        // This is approx 1.04ms per byte. We add 5ms for OS overhead.
        long guardTime = (long)((bytesSent * 1000) / 9600) + 5;

        // 3. Wait for the physical transmission to finish
        Thread.sleep(guardTime);
        
        deRePin.low(); // Return to Listener Mode immediately
    }

    private static void updateThalamus(long pid, String status) {
        try {
            JSONObject pulse = new JSONObject();
            pulse.put("organ", "Pons");
            pulse.put("pid", pid);
            pulse.put("status", status);
            pulse.put("timestamp", System.currentTimeMillis());
            Files.writeString(Paths.get(THALAMUS_PULSE), pulse.toString());
        } catch (Exception e) {
            // Silently fail pulse to keep bus processing priority
        }
    }
}

