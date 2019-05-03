package org.gheith.gameboy;

import javax.sound.sampled.*;
import java.security.InvalidParameterException;
import java.util.Arrays;

class SoundChip {
    public SquareWave square1 = new SquareWave();
    public SquareWave square2 = new SquareWave();
    public WaveChannel waveChannel = new WaveChannel();
    
    public static final int SAMPLE_RATE = 131072 / 3;
    public static final int SAMPLES_PER_FRAME = SAMPLE_RATE/16;
    public static final AudioFormat AUDIO_FORMAT = new AudioFormat(SAMPLE_RATE,  8, 1, false, false);
    
    private SourceDataLine sourceDL;
    
    byte[] masterBuffer = new byte[SAMPLES_PER_FRAME];
    byte[] tempBuffer = new byte[SAMPLES_PER_FRAME];

    SoundChip() {
        try {
            sourceDL = AudioSystem.getSourceDataLine(AUDIO_FORMAT);
            sourceDL.open(AUDIO_FORMAT);
            sourceDL.start();
        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    public void tick() {
        int samplesToWrite = Math.min(sourceDL.available(), SAMPLES_PER_FRAME);
        
        Arrays.fill(masterBuffer, (byte) 0);
        
        boolean channelEnabled = square1.tick(tempBuffer, samplesToWrite);
        
        if(channelEnabled) {
            for (int i = 0; i < samplesToWrite; i++) {
                masterBuffer[i] += (byte) (tempBuffer[i] / 4);
            }
        }
        
        channelEnabled = square2.tick(tempBuffer, samplesToWrite);
        
        if(channelEnabled) {
            for (int i = 0; i < samplesToWrite; i++) {
                masterBuffer[i] += (byte) (tempBuffer[i] / 4);
            }
        }

        channelEnabled = waveChannel.tick(tempBuffer, samplesToWrite);

        if(channelEnabled) {
            for (int i = 0; i < samplesToWrite; i++) {
                masterBuffer[i] += (byte) (tempBuffer[i] / 4);
            }
        }
        
        sourceDL.write(masterBuffer, 0, samplesToWrite);
    }
}

public interface SoundChannel {
    void handleByte(int location, int toWrite);
    boolean tick(byte[] soundBuffer, int samplesToWrite);
}

class SquareWave implements SoundChannel {
    protected int duty = 0;
    protected int lengthLoad = 0;
    protected int startingVolume = 0;
    protected boolean envelopeAdd = false;
    protected int envelopePeriod = 0;
    protected int frequency = 0;
    protected boolean playing = false;
    protected boolean lengthEnabled = false;
    protected int lengthCounter = 0;

    protected int currentVolume = 0;
    protected long ticks = 0;

    private byte[] transition = new byte[SAMPLE_RATE];

    public static final int SAMPLE_RATE = SoundChip.SAMPLE_RATE;

    public static int getWaveform(int duty) {
        switch(duty){
            case 0:
                return 0b00100000;
            case 1:
                return 0b00110000;
            case 2:
                return 0b00111100;
            case 3:
                return 0b01111110;
            default:
                throw new IllegalArgumentException("duty must be in [0,4)");
        }
    }

    //tick is 20 hz
    public boolean tick(byte[] soundBuffer, int samplesToWrite) {
        if(!this.playing){
            return false;
        }
        
        ticks++;

        if (lengthEnabled) {
            lengthCounter-= 4;
            if (lengthCounter <= 0) {
                this.playing = false;
            }
        }

        if(envelopePeriod != 0 && ticks % envelopePeriod == 0) {
            this.currentVolume += (envelopeAdd? 3: -3);
            if(this.currentVolume < 0) this.currentVolume = 0;
            if(this.currentVolume > 15) this.currentVolume = 15;
        }

        int waveForm = getWaveform(this.duty);
        double chunkSize = (2048.0 - frequency) / 8 / 3;

        int waveLength = (int)(8 * chunkSize);

        for(int i = 0; i < waveLength; i++){
            int loc = (int)(i / chunkSize);
            soundBuffer[i] = (((waveForm >> loc) & 1) == 1)? (byte)(currentVolume): (byte)0;
        }

        //replicate the wave until there's (0, waveLength] bytes left to write
        int samplesWritten;
        for(samplesWritten = waveLength; samplesWritten < samplesToWrite - waveLength; samplesWritten += waveLength){
            System.arraycopy(soundBuffer, 0, soundBuffer, samplesWritten, waveLength); 
        }

        int transitionSamples = samplesToWrite - samplesWritten;
        for(int i = 0; i < transitionSamples; i++){
            //I linearly decrease the amplitude to prevent a popping sound
            //transition samples are negative because all duties end on 0
            soundBuffer[samplesWritten + i] = (byte)((transitionSamples - i) * (currentVolume) / transitionSamples);
        }
        
        return true;
    }

    @Override
    //location is 0, 1, 2, 3, 4
    public void handleByte(int location, int toWrite) {
        switch(location){
            case 0:
                //do nothing
                break;
            case 1:
                this.duty = (toWrite >> 6) & 0x3;
                this.lengthLoad = toWrite & 0x3f;
                break;
            case 2:
                this.startingVolume = (toWrite >> 4) & 0xf;
                this.currentVolume = this.startingVolume;
                this.envelopeAdd = ((toWrite >> 3) & 1) == 1;
                this.envelopePeriod = toWrite & 0x7;
                break;
            case 3:
                this.frequency = (this.frequency >> 8) << 8;
                this.frequency |= toWrite & 0xff;
                break;
            case 4:
                this.playing = (toWrite >> 7) == 1;
                this.lengthEnabled = (toWrite >> 6) == 1;
                this.frequency &= 0xff;
                this.frequency |= ((toWrite & 0x7) << 8);
        }

        if(this.lengthEnabled){
            this.lengthCounter = this.lengthLoad;
            System.out.println(this.lengthCounter);
        }
    }
}

class WaveChannel implements SoundChannel {
    protected boolean dacPower = false;
    protected int lengthLoad = 0;
    protected int volumeCode = 0;
    protected int frequency = 0;
    protected boolean playing = false;
    protected boolean lengthEnabled = false;
    protected int lengthCounter = 0;
    
    protected byte[] samples = new byte[32];

    public static final int SAMPLE_RATE = SoundChip.SAMPLE_RATE;
    
    public void handleWaveByte(int location, int toWrite) {
        if(location > 15 || location < 0){
            throw new IllegalArgumentException("only 16 wave bytes");
        }
        
        samples[2 * location] = (byte)(toWrite >> 4);
        samples[2 * location + 1] = (byte)(toWrite & 0xf);
    }

    @Override
    public void handleByte(int location, int toWrite) {
        switch(location){
            case 0:
                this.dacPower = ((toWrite >> 7) & 1) == 1;
                break;
            case 1:
                this.lengthLoad = toWrite;
                break;
            case 2:
                this.volumeCode = (toWrite >> 5) & 3;
                break;
            case 3:
                this.frequency = (this.frequency >> 8) << 8;
                this.frequency |= toWrite & 0xff;
                break;
            case 4:
                this.playing = (toWrite >> 7) == 1;
                this.lengthEnabled = (toWrite >> 6) == 1;
                this.frequency &= 0xff;
                this.frequency |= ((toWrite & 0x7) << 8);
        }
    }
    
    private byte volumeAdjust(byte toAdjust) {
        switch(volumeCode) {
            case 0:
                return 0;
            case 1:
                return toAdjust;
            case 2:
                return (byte)(toAdjust / 2);
            case 3:
                return (byte)(toAdjust / 4);
        }
        throw new InvalidParameterException("invalid volume code");
    }

    @Override
    public boolean tick(byte[] soundBuffer, int samplesToWrite) {
        if(!this.playing){
            return false;
        }
        
        if (lengthEnabled) {
            lengthCounter-= 4;
            if (lengthCounter <= 0) {
                this.playing = false;
            }
        }
        
        double chunkSize = (2048.0 - frequency) / 16 / 3;

        int waveLength = (int)(32 * chunkSize);

        for(int i = 0; i < waveLength; i++){
            int loc = (int)(i / chunkSize);
            soundBuffer[i] = volumeAdjust(samples[loc]);
        }

        int samplesWritten;
        for(samplesWritten = 0; samplesWritten < samplesToWrite - waveLength; samplesWritten += waveLength){
            System.arraycopy(soundBuffer, 0, soundBuffer, samplesWritten, waveLength);
        }

        int transitionSamples = samplesToWrite - samplesWritten;
        for(int i = 0; i < transitionSamples; i++){
            soundBuffer[samplesWritten + i] = (byte)((transitionSamples - i) * (soundBuffer[waveLength-1]) / transitionSamples);
        }
        
        return true;
    }
}