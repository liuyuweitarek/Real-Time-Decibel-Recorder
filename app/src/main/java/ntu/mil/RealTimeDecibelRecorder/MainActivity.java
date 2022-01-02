package ntu.mil.RealTimeDecibelRecorder;

import android.Manifest;
import android.graphics.Color;
import android.media.MediaActionSound;
import android.os.Bundle;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;




import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public final static String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_PERMISSIONS = 786;
    private Button btnStartRecord, btnStopRecord;
    public TextView txtDbValue, txtRecordFileName;
    private LineChart mLineChart;
    private VoiceRecorder mVoiceRecorder;
    private MediaActionSound mMediaActionSound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPermissions();


        btnStartRecord = (Button) findViewById(R.id.btnStartRecord);
        btnStartRecord.setOnClickListener(this);

        btnStopRecord = (Button) findViewById(R.id.btnStopRecord);
        btnStopRecord.setOnClickListener(this);
        btnStopRecord.setVisibility(View.GONE);

        txtDbValue = (TextView) findViewById(R.id.txtDbValue);
        txtRecordFileName = findViewById(R.id.txtRecordFileName);

        mLineChart = findViewById(R.id.liveChart);

        mVoiceRecorder = new VoiceRecorder(this, voiceCallback);
        mMediaActionSound = new MediaActionSound();
        initChart();

    }
    @Override
    public void onClick(View v) {
        switch(v.getId()) {
            case R.id.btnStartRecord:
                btnStartRecord.setVisibility(View.GONE);
                btnStopRecord.setVisibility(View.VISIBLE);
                startVoiceRecorder();
                break;

            case R.id.btnStopRecord:
                btnStopRecord.setVisibility(View.GONE);
                btnStartRecord.setVisibility(View.VISIBLE);
                stopVoiceRecorder();
                txtDbValue.setText("Not hearing...");
                break;

            default:
                break;
        }
    }

    private LineDataSet createSet(String SetName) {
        LineDataSet set = new LineDataSet(null, SetName);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(Color.GRAY);
        set.setLineWidth(2);
        set.setDrawCircles(false);
        set.setFillColor(Color.RED);
        set.setFillAlpha(50);
        set.setDrawFilled(true);
        set.setValueTextColor(Color.BLACK);
        set.setDrawValues(false);
        return set;
    }

    private void initChart(){
        mLineChart.getDescription().setEnabled(false);// Tag
        mLineChart.setTouchEnabled(true);// Touchable
        mLineChart.setDragEnabled(true);// Interactive

        // Set a basic line
        LineData data = new LineData();
        data.setValueTextColor(Color.BLACK);
        mLineChart.setData(data);

        // Bottom left tags
        Legend l =  mLineChart.getLegend();
        l.setForm(Legend.LegendForm.LINE);
        l.setTextColor(Color.BLACK);

        // X Axis
        XAxis x =  mLineChart.getXAxis();
        x.setTextColor(Color.BLACK);
        x.setDrawGridLines(true);//畫X軸線
        x.setPosition(XAxis.XAxisPosition.BOTTOM);//把標籤放底部
        x.setLabelCount(5,true);//設置顯示5個標籤

        // Content of X axis
        x.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return "No. "+Math.round(value);
            }
        });

        YAxis y = mLineChart.getAxisLeft();
        y.setTextColor(Color.BLACK);
        y.setDrawGridLines(true);
        y.setAxisMaximum(16000);
        y.setAxisMinimum(0);
        mLineChart.getAxisRight().setEnabled(false);// Right YAxis invisible
        mLineChart.setVisibleXRange(0,100);// Set visible range
    }

    private void addData(int inputData){
        LineData data =  mLineChart.getData();

        // DB data only, set to 0. If other type of data be added, set to other int.
        ILineDataSet set = data.getDataSetByIndex(0);
        if (set == null){
            set = createSet("DB_DATA");
            data.addDataSet(set);
        }
        data.addEntry(new Entry(set.getEntryCount(),inputData),0);

        // Renew plot
        data.notifyDataChanged();
        mLineChart.notifyDataSetChanged();
        mLineChart.setVisibleXRange(0,100); // Visible range
        mLineChart.moveViewToX(data.getEntryCount());// Whether track on the newest data point
    }



    private void requestPermissions() {
        Log.i(TAG, "requestPermissions()");
        String[] permissions = new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO};
        requestPermissions(permissions, REQUEST_PERMISSIONS);
    }

    private void startVoiceRecorder(){
        if(mVoiceRecorder != null){
            mMediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING);
            mVoiceRecorder.start();
        }
    }

    private void stopVoiceRecorder(){
        if(mVoiceRecorder != null){
            mMediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING);
            mVoiceRecorder.stop();
        }
    }
    private final VoiceRecorder.Callback voiceCallback = new VoiceRecorder.Callback() {

        private String byteToDbValue(byte[] buffer, int size){
            for (int i = 0; i < size - 1; i += 2) {
                // The buffer has LINEAR16 in little endian.
                int s = buffer[i + 1];
                if (s < 0) s *= -1;
                s <<= 8;
                s += Math.abs(buffer[i]);
                return Integer.toString(s);
            }
            return "";
        }

        @Override
        public void onVoiceStart(final String recordFilename) {
            Log.d(TAG, "onVoiceStart()");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    txtRecordFileName.setText(recordFilename);
                }
            });
        }

        @Override
        public void onVoice(final byte[] data, final int size, boolean sentenceCompleted) {
            Log.d(TAG, "onVoice()");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String db_value = byteToDbValue(data,size);
                    addData(Integer.valueOf(db_value));
                    txtDbValue.setText(db_value);
                }
            });
        }

        @Override
        public void onVoiceEnd() {
            Log.d(TAG, "onVoiceEnd()");
            stopVoiceRecorder();
        }
    };


    @Override
    protected void onDestroy(){
        if(mMediaActionSound != null){
            mMediaActionSound.release();
            mMediaActionSound = null;
        }
        super.onDestroy();
    }

}
