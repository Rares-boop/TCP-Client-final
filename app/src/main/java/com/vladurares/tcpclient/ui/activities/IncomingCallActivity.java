package com.vladurares.tcpclient.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.utils.ClientKeyManager;

import chat.network.NetworkPacket;
import chat.network.PacketType;


public class IncomingCallActivity extends AppCompatActivity {
    private Ringtone ringtone;
    private Vibrator vibrator;
    private int callerId;
    private String callerName;
    private int chatId;
    private String serverIp;
    private boolean isAudio;
    private static final String TAG = "IncomingCallActivity";
    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    private ClientKeyManager keyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        callerId = getIntent().getIntExtra("CALLER_ID", -1);
        callerName = getIntent().getStringExtra("CALLER_NAME");
        chatId = getIntent().getIntExtra("CHAT_ID", -1);
        serverIp = getIntent().getStringExtra("SERVER_IP");
        isAudio = getIntent().getBooleanExtra("IS_AUDIO", true);

        TextView txtName = findViewById(R.id.txtCallerName);
        txtName.setText(callerName != null ? callerName : "Unknown Caller");

        startRinging();

        findViewById(R.id.btnAnswer).setOnClickListener(v -> answerCall());
        findViewById(R.id.btnDecline).setOnClickListener(v -> declineCall());

        keyManager = new ClientKeyManager(this, TcpConnection.getCurrentUserId());
    }

    @Override
    protected void onResume() {
        super.onResume();
        TcpConnection.setPacketListener(this::handlePacketOnUI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TcpConnection.setPacketListener(null);
    }

    private void handlePacketOnUI(NetworkPacket packet) {
        runOnUiThread(() -> {
            try {
                switch (packet.getType()) {
                    case CALL_END:
                        android.widget.Toast.makeText(this, "Call cancelled by caller.", android.widget.Toast.LENGTH_SHORT).show();
                        stopRinging();
                        finish();
                        break;

                    case DELETE_CHAT_BROADCAST:
                        int deletedChatId = gson.fromJson(packet.getPayload(), Integer.class);
                        if (deletedChatId == chatId) {
                            android.widget.Toast.makeText(this, "Chat was deleted! Cancelling call.", android.widget.Toast.LENGTH_SHORT).show();
                            stopRinging();
                            finish();
                        }
                        break;

                    case RENAME_CHAT_BROADCAST:
                        chat.network.ChatDtos.RenameGroupDto renameDto = gson.fromJson(packet.getPayload(),
                                chat.network.ChatDtos.RenameGroupDto.class);
                        if (renameDto.chatId == chatId) {
                            callerName = renameDto.newName;
                            TextView txtName = findViewById(R.id.txtCallerName);
                            txtName.setText(callerName);
                            Log.i(TAG, "New name: " + callerName);
                        }
                        break;

                    case CREATE_CHAT_BROADCAST:
                        chat.network.ChatDtos.NewChatBroadcastDto broadcastDto = gson.fromJson(packet.getPayload(), chat.network.ChatDtos.NewChatBroadcastDto.class);
                        if (broadcastDto.keyCiphertext != null && !broadcastDto.keyCiphertext.isEmpty()) {
                            byte[] cipherBytes = android.util.Base64.decode(broadcastDto.keyCiphertext, android.util.Base64.NO_WRAP);
                            String myPrivStr = keyManager.getMyPreKeyPrivateKey();
                            java.security.PrivateKey myPriv = chat.security.CryptoHelper.stringToKyberPrivate(myPrivStr);
                            javax.crypto.SecretKey shared = chat.security.CryptoHelper.decapsulate(myPriv, cipherBytes);
                            String keyBase64 = android.util.Base64.encodeToString(shared.getEncoded(), android.util.Base64.NO_WRAP);

                            keyManager.saveKey(broadcastDto.groupInfo.getId(), keyBase64);
                            Log.i(TAG, "[BOB] Key saved in background while phone was ringing!");
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling packet in IncomingCallActivity", e);
            }
        });
    }
    private void startRinging() {
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), notification);
            ringtone.play();

            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = {0, 1000, 1000};
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ringtone or vibrator", e);
        }
    }

    private void stopRinging() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    private void answerCall() {
        stopRinging();
        sendTcpResponse(PacketType.CALL_ACCEPT);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("TARGET_USER_ID", callerId);
        intent.putExtra("CHAT_ID", chatId);
        intent.putExtra("USERNAME", callerName);
        intent.putExtra("SERVER_IP", serverIp);
        intent.putExtra("MY_USER_ID", TcpConnection.getCurrentUserId());
        intent.putExtra("IS_AUDIO", isAudio);

        startActivity(intent);
        finish();
    }

    private void declineCall() {
        stopRinging();
        sendTcpResponse(PacketType.CALL_DENY);

        finish();
    }

    private void sendTcpResponse(PacketType type) {
        TcpConnection.sendPacket(
                new NetworkPacket(type, TcpConnection.getCurrentUserId(), callerId)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
    }
}
