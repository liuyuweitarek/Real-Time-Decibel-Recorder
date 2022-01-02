package ntu.mil.RealTimeDecibelRecorder;


import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VoiceRecorder {
    private static final String TAG = VoiceRecorder.class.getSimpleName();
    private static final int[] SAMPLE_RATE_CANDIDATES = new int[]{16000, 11025, 22050, 44100};
    private static final int SAMPLE_RATE = 16000;
    private static final int RECORDER_BPP = 16;

    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private static final int AMPLITUDE_THRESHOLD = 1500;
    private static final int SPEECH_TIMEOUT_MILLIS = 3000;
    private static final int MAX_SPEECH_LENGTH_MILLIS = 30 * 1000;

    private static final String AUDIO_RECORDER_FILE_EXT_WAV = ".wav";
    private static final String AUDIO_RECORDER_FOLDER = "Music";
    private static final String AUDIO_RECORDER_TEMP_FILE = "record_temp.wav";

    public static abstract class Callback {
        public void onVoiceStart(String recordFilename) {
        }
        public void onVoice(byte[] data, int size, boolean sentenceCompleted) {
        }
        public void onVoiceEnd() {
        }
    }

    private final Context mContext;
    private final Callback mCallback;
    private Thread recordThread;
    private AudioRecord mAudioRecord;
    private boolean isRecording = false;
    private byte[] mBuffer;
    private int bufferSize;
    private final Object mLock = new Object();
    private String mFilename, mTempFilename, mUIFilename;
    private long mLastVoiceHeardMillis = Long.MAX_VALUE;
    private long mVoiceStartedMillis;
    private boolean sentenceCompleted ;
    public static String getMIMEType(String url) {
        String mType = null;
        String mExtension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (mExtension != null) {
            mType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(mExtension);
        }
        return mType;
    }
    public VoiceRecorder(Context context, @NonNull Callback callback) {
        mContext = context;
        mCallback = callback;
    }
    public void start() {
        Log.d(TAG, "start()");

        // Try to create a new recording session.
        mAudioRecord = createAudioRecord();
        if (mAudioRecord == null)
            throw new RuntimeException("Cannot instantiate VoiceRecorder");

        // Start recording.
        mAudioRecord.startRecording();
        isRecording = true;

        recordThread = new Thread(new ProcessVoice());
        recordThread.start();

    }
    public void stop() {
        Log.d(TAG, "stop()");

        isRecording = false;

        synchronized (mLock) {
            dismiss();
            if (recordThread != null) {
                recordThread.interrupt();
                recordThread = null;
            }
            if (mAudioRecord != null) {

                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            }
            mBuffer = null;

            copyWavFile(mTempFilename, mFilename);
            deleteTempFile();
        }
    }

    public void dismiss() {
        if (mLastVoiceHeardMillis != Long.MAX_VALUE ) {
            mLastVoiceHeardMillis = Long.MAX_VALUE;
            mCallback.onVoiceEnd();
        }
    }

    private AudioRecord createAudioRecord() {
        for (int sampleRate : SAMPLE_RATE_CANDIDATES) {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING);
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                continue;
            }
            final AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, CHANNEL, ENCODING, bufferSize);
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                mBuffer = new byte[bufferSize];
                return audioRecord;
            } else
                audioRecord.release();
        }
        return null;
    }

    private class ProcessVoice implements Runnable {
        @Override
        public void run() {
            mTempFilename = getTempFilename();
            mFilename = getFilename();
            FileOutputStream os = null;
            try {
                os = new FileOutputStream(mTempFilename);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            while (isRecording) {
                synchronized (mLock) {
                    if (Thread.currentThread().isInterrupted()) {
                        Log.d(TAG,"ProcessVoice_isInterrupt");
                        break;
                    }
                    // Read voice bytes here. mBuffer has the info that we want to deal with.
                    final int read = mAudioRecord.read(mBuffer, 0, mBuffer.length);
                    final long now = System.currentTimeMillis();

                    // Record file here
                    if (AudioRecord.ERROR_INVALID_OPERATION != read) {
                        try {
                            os.write(mBuffer);
                        } catch (IOException e) {
                            end();
                            try {
                                os.close();
                            } catch (IOException err) {
                                err.printStackTrace();
                            }
                            e.printStackTrace();
                        }
                    }

                    // If it doesn't hear any voice for three seconds, then stop recording the voice.
                    if (isHearingVoice(mBuffer, read)) {
                        if (mLastVoiceHeardMillis == Long.MAX_VALUE) {
                            mVoiceStartedMillis = now;
                            sentenceCompleted = false;
                            mCallback.onVoiceStart(mUIFilename);
                        }

                        if (now - mVoiceStartedMillis > MAX_SPEECH_LENGTH_MILLIS) {
                            sentenceCompleted = true;
                        }

                        mCallback.onVoice(mBuffer, read, sentenceCompleted);
                        mLastVoiceHeardMillis = now;
                        if (now - mVoiceStartedMillis > MAX_SPEECH_LENGTH_MILLIS) {
                            end();
                            try {
                                os.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    } else if (mLastVoiceHeardMillis != Long.MAX_VALUE) {
                        if (now - mLastVoiceHeardMillis > SPEECH_TIMEOUT_MILLIS) {
                            sentenceCompleted = true;
                        }
                        mCallback.onVoice(mBuffer, read, sentenceCompleted);
                        if (now - mLastVoiceHeardMillis > SPEECH_TIMEOUT_MILLIS) {
                            end();
                            try {
                                os.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
        private void end() {
            mLastVoiceHeardMillis = Long.MAX_VALUE;
            mCallback.onVoiceEnd();
        }

        private boolean isHearingVoice(byte[] buffer, int size) {
            for (int i = 0; i < size - 1; i += 2) {
                // The buffer has LINEAR16 in little endian.
                int s = buffer[i + 1];
                if (s < 0) s *= -1;
                s <<= 8;
                s += Math.abs(buffer[i]);
                if (s > AMPLITUDE_THRESHOLD) {
                    return true;
                }
            }
            return false;
        }
    }
    private void copyWavFile(String in, String out){
        try{
            FileInputStream is = new FileInputStream(in);
            FileOutputStream os = new FileOutputStream(out);

            long totalAudioLength = is.getChannel().size();
            long totalDataLength = totalAudioLength + 36;
            int channels = CHANNEL == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
            long byteRate = RECORDER_BPP * SAMPLE_RATE * channels / 8;
            byte[] data = new byte[bufferSize];

            writeWaveFileHeader(os, totalAudioLength, totalDataLength, SAMPLE_RATE, channels, byteRate);

            while(is.read(data) != -1){
                os.write(data);
            }

            is.close();
            os.close();

        }catch (IOException e){
            e.printStackTrace();
        }
    }

    private void writeWaveFileHeader(FileOutputStream out, long totalAudioLen, long totalDataLen, long longSampleRate, int channels, long byteRate) throws IOException {

        byte[] header = new byte[44];

        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (2 * 16 / 8); // block align
        header[33] = 0;
        header[34] = RECORDER_BPP; // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    private String getFilename(){

        String filepath = Environment.getExternalStorageDirectory().getPath();
        File file = new File(filepath,AUDIO_RECORDER_FOLDER);
        if(!file.exists()){
            boolean created = file.mkdirs();
            Log.d(TAG, "File created: " + created);
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.MIME_TYPE, getMIMEType(file.getAbsolutePath() + "/" + System.currentTimeMillis() + AUDIO_RECORDER_FILE_EXT_WAV));
        values.put(MediaStore.Video.Media.TITLE, file.getName());
        values.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());
        mContext.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

        SimpleDateFormat sdf = new SimpleDateFormat("dd_HH_mm_ss");
        Date filedate = new Date(System.currentTimeMillis());
        String filename = sdf.format(filedate);
        mUIFilename = filename + AUDIO_RECORDER_FILE_EXT_WAV;
        return (file.getAbsolutePath() + "/" + filename + AUDIO_RECORDER_FILE_EXT_WAV);
    }

    private String getTempFilename(){
        String filepath = Environment.getExternalStorageDirectory().getPath();
        File file = new File(filepath,AUDIO_RECORDER_FOLDER);

        if(!file.exists()){
            boolean created = file.mkdirs();
            Log.d(TAG, "File created: " + created);
            Log.d(TAG, "File created: " + filepath);
        }

        File tempFile = new File(file.getAbsolutePath(),AUDIO_RECORDER_TEMP_FILE);

        if(tempFile.exists()){
            boolean deleted = tempFile.delete();
            Log.d(TAG, "File deleted: " + deleted);
        }

        return (file.getAbsolutePath() + "/" + AUDIO_RECORDER_TEMP_FILE);
    }

    private void deleteTempFile() {
        File file = new File(getTempFilename());

        boolean deleted = file.delete();
        Log.d(TAG, "File deleted:" + deleted);
    }
}
