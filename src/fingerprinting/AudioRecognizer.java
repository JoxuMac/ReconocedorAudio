package fingerprinting;

import serialization.Serialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import utilities.HashingFunctions;
import utilities.Spectrum;

public class AudioRecognizer {
    
    // The main hashtable required in our interpretation of the algorithm to
    // store the song repository
    private Map<Long, List<KeyPoint>> hashMapSongRepository;
    
    // Variable to stop/start the listening loop
    public boolean running;

    // Constructor
    public AudioRecognizer() {
        
        // Deserialize the hash table hashMapSongRepository (our song repository)
        this.hashMapSongRepository = Serialization.deserializeHashMap();
        this.running = true;
    }

    // Method used to acquire audio from the microphone and to add/match a song fragment
    public void listening(String songId, boolean isMatching) throws LineUnavailableException {
        
        // Fill AudioFormat with the recording we want for settings
        AudioFormat audioFormat = new AudioFormat(AudioParams.sampleRate,
                AudioParams.sampleSizeInBits, AudioParams.channels,
                AudioParams.signed, AudioParams.bigEndian);
        
        // Required to get audio directly from the microphone and process it as an 
        // InputStream (using TargetDataLine) in another thread      
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        line.start();
        
        Thread listeningThread = new Thread(new Runnable() {
                        
            @Override
            public void run() {
                // Output stream 
                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                // Reader buffer
                byte[] buffer = new byte[AudioParams.bufferSize];         
                try {
                    while (running) {
                        // Reading
                        int count = line.read(buffer, 0, buffer.length);
                        // If buffer is not empty
                        if (count > 0) {
                            outStream.write(buffer, 0, count);
                        }
                    }

                    byte[] audioTimeDomain = outStream.toByteArray();

                    // Compute magnitude spectrum
                    double [][] magnitudeSpectrum = Spectrum.compute(audioTimeDomain);                    
                    // Determine the shazam action (add or matching) and perform it
                    shazamAction(magnitudeSpectrum, songId, isMatching);                    
                    // Close stream
                    outStream.close();                    
                    // Serialize again the hashMapSongRepository (our song repository)
                    Serialization.serializeHashMap(hashMapSongRepository);                
                } catch (IOException e) {
                    System.err.println("I/O exception " + e);
                    System.exit(-1);
                }
            }
        });

        // Start listening
        listeningThread.start();
        
        System.out.println("Press ENTER key to stop listening...");
        try {
            System.in.read();
        } catch (IOException ex) {
            Logger.getLogger(AudioRecognizer.class.getName()).log(Level.SEVERE, null, ex);
        }
        this.running = false;               
    }   
    
    // Determine the shazam action (add or matching a song) and perform it 
    private void shazamAction(double[][] magnitudeSpectrum, String songId, boolean isMatching) {  
        
    	// Hash table used for matching (Map<songId, Map<offset,count>>)
        Map<String, Map<Integer,Integer>> matchMap = 
                new HashMap<String, Map<Integer,Integer>>(); 
    
        // Iterate over all the chunks/ventanas from the magnitude spectrum
        for (int c = 0; c < magnitudeSpectrum.length; c++) { 
            // Compute the hash entry for the current chunk/ventana (magnitudeSpectrum[c])
            long chunk = computeHashEntry(magnitudeSpectrum[c]);
            
            // In the case of adding the song to the repository
            if (!isMatching) { 
                // Adding keypoint to the list in its relative hash entry which has been computed before
                KeyPoint point = new KeyPoint(songId, c);
                
                if(!hashMapSongRepository.containsKey(chunk))
                	hashMapSongRepository.put(chunk, new ArrayList<KeyPoint>());
                
                hashMapSongRepository.get(chunk).add(point);
            }
            // In the case of matching a song fragment
            else {
                // Iterate over the list of keypoints that matches the hash entry
                // in the the current chunk
            	List<KeyPoint> keypoints=new ArrayList<KeyPoint>();
            	
        		if(hashMapSongRepository.containsKey(chunk))
        			keypoints=hashMapSongRepository.get(chunk);
    		
                // For each keypoint:
    			for(int kp=0;kp<keypoints.size();kp++){   
                    // Compute the time offset (Math.abs(point.getTimestamp() - c))
    				int offset=Math.abs(keypoints.get(kp).getTimestamp() - c);
    				
                    // Now, focus on the matchMap hashtable:
                    // If songId (extracted from the current keypoint) has not been found yet in the matchMap add it
    				if(!matchMap.containsKey(keypoints.get(kp).getSongId())){
    					HashMap<Integer, Integer> tmpHashMap = new HashMap<Integer, Integer>() ; 
    					tmpHashMap.put(offset, 1);
                		matchMap.put(keypoints.get(kp).getSongId(),tmpHashMap);
                    // (else) songId has been added in a past chunk
                     } else {
                    	 Map <Integer, Integer> map = new HashMap<Integer, Integer>();
                    	 map=matchMap.get(keypoints.get(kp).getSongId());
                    	 
                        // If this is the first time the computed offset appears for this particular songId
                    	 if(!map.containsKey(offset)) 
                    		 map.put(offset, 1);
                        // (else) 
                     	 else 
                     		map.put(offset, map.get(offset)+1);
                     }
    			}
            }
        } // End iterating over the chunks/ventanas of the magnitude spectrum
        // If we chose matching, we 
        if (isMatching) {
           showBestMatching(matchMap);
        }
    }
    
    // Find out in which range the frequency is
    private int getIndex(int freq) {
       
        int i = 0;
        while (AudioParams.range[i] < freq) {
            i++;
        }
        return i;
    }  
    
    // Compute hash entry for the chunk/ventana spectra 
    private long computeHashEntry(double[] chunk) {
                
        // Variables to determine the hash entry for this chunk/window spectra
        double highscores[] = new double[AudioParams.range.length];
        int frequencyPoints[] = new int[AudioParams.range.length];
       
        for (int freq = AudioParams.lowerLimit; freq < AudioParams.unpperLimit - 1; freq++) {
            // Get the magnitude
            double mag = chunk[freq];
            // Find out which range we are in
            int index = getIndex(freq);
            // Save the highest magnitude and corresponding frequency:
            if (mag > highscores[index]) {
                highscores[index] = mag;
                frequencyPoints[index] = freq;
            }
        }        
        // Hash function 
        return HashingFunctions.hash1(frequencyPoints[0], frequencyPoints[1], 
                frequencyPoints[2],frequencyPoints[3],AudioParams.fuzzFactor);
    }
    
    // Method to find the songId with the most frequently/repeated time offset
    private void showBestMatching(Map<String, Map<Integer,Integer>> matchMap) {
    	
    	int bestcount = 0;
    	String bestsong = "";

    	// Iterate over the songs in the hashtable used for matching (matchMap)
    	for(String id : matchMap.keySet()) {
			Map<Integer, Integer> tmpMap = matchMap.get(id);
			int bestCountForSong = 0;

			 // (For each song) Iterate over the nested hashtable Map<offset,count>
			for (Map.Entry<Integer, Integer> entry : tmpMap.entrySet()) 
				// Get the biggest offset for the current song and update (if necessary)
	            // the best overall result found till the current iteration
				if (entry.getValue() > bestCountForSong) 
					bestCountForSong = entry.getValue();
			
			if (bestCountForSong > bestcount) {
				bestcount = bestCountForSong;
				bestsong = id;
			}
		}
        // Print the songId string which represents the best matching     
        System.out.println("Best song: "+ bestsong);
    }
}