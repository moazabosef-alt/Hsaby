package com.azha.hisabati;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReminderReceiver extends BroadcastReceiver {
    private static final String STORE="hisabati_notification_config";
    private static final String CHANNEL="hisabati_reminders";
    private static final String TASK="com.azha.hisabati.TASK_ALERT";
    private static final String DAILY="com.azha.hisabati.DAILY_ALERT";
    private static final String DUE="com.azha.hisabati.JOB_DUE_ALERT";

    static void configure(Context context,int hours,String time,int unfinished,String jobSnapshot){
        int safeHours=(hours==3||hours==4||hours==5)?hours:3;
        String safeTime=time!=null&&time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")?time:"21:00";
        // Store only a small local summary: status + due time, no customer names or amounts.
        String safeJobs=jobSnapshot!=null&&jobSnapshot.length()<500_000?jobSnapshot:"[]";
        context.getSharedPreferences(STORE,Context.MODE_PRIVATE).edit()
          .putInt("hours",safeHours).putString("dailyTime",safeTime).putInt("unfinished",Math.max(0,unfinished))
          .putString("pendingJobs",safeJobs).apply();
        schedule(context);
    }
    static void clear(Context context){
        AlarmManager alarms=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        if(alarms!=null){ alarms.cancel(pending(context,TASK,10));alarms.cancel(pending(context,DAILY,11));alarms.cancel(pending(context,DUE,12)); }
        context.getSharedPreferences(STORE,Context.MODE_PRIVATE).edit().clear().apply();
    }
    static void schedule(Context context){
        SharedPreferences p=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
        AlarmManager alarms=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        if(alarms==null)return;
        PendingIntent task=pending(context,TASK,10),daily=pending(context,DAILY,11),due=pending(context,DUE,12);
        alarms.cancel(task);alarms.cancel(daily);alarms.cancel(due);
        if(p.getInt("unfinished",0)>0){
            long period=p.getInt("hours",3)*AlarmManager.INTERVAL_HOUR;
            alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+period,period,task);
            long closest=nextDeadline(p);
            if(closest>0)alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,closest,due);
        }
        String[] hm=p.getString("dailyTime","21:00").split(":");
        Calendar next=Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0]));
        next.set(Calendar.MINUTE,Integer.parseInt(hm[1]));
        next.set(Calendar.SECOND,0);
        next.set(Calendar.MILLISECOND,0);
        if(next.getTimeInMillis()<=System.currentTimeMillis())next.add(Calendar.DAY_OF_YEAR,1);
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP,next.getTimeInMillis(),AlarmManager.INTERVAL_DAY,daily);
    }
    private static JSONArray pendingJobs(SharedPreferences p){
        try{return new JSONArray(p.getString("pendingJobs","[]"));}catch(Exception ignored){return new JSONArray();}
    }
    private static long nextDeadline(SharedPreferences p){
        long now=System.currentTimeMillis(),closest=Long.MAX_VALUE;
        JSONArray jobs=pendingJobs(p);
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.optJSONObject(i);if(job==null)continue;
            if("delivered".equals(job.optString("status")))continue;
            long dueAt=job.optLong("dueAt",0);
            if(dueAt>now && dueAt<closest)closest=dueAt;
        }
        return closest==Long.MAX_VALUE?0:closest;
    }
    private static String unfinishedDescription(SharedPreferences p,boolean dueOnly){
        int fresh=0,working=0,late=0,dueNow=0;long now=System.currentTimeMillis();
        JSONArray jobs=pendingJobs(p);
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.optJSONObject(i);if(job==null)continue;
            String status=job.optString("status","");if("delivered".equals(status))continue;
            if("not_started".equals(status))fresh++;else working++;
            long dueAt=job.optLong("dueAt",0);
            if(dueAt>0&&dueAt<=now){late++;if(dueAt>now-2*60*60*1000)dueNow++;}
        }
        if(dueOnly)return "حان موعد تسليم "+dueNow+" شغل. افتح حساباتي وراجع مواعيد التسليم.";
        int count=fresh+working;
        if(count==0)return "لا توجد أشغال معلقة";
        return "لديك "+fresh+" شغل لم يبدأ، و"+working+" قيد الانتظار أو التنفيذ"+
                (late>0?"، منها "+late+" متأخرة عن التسليم":"")+". افتح حساباتي للمتابعة.";
    }
    private static PendingIntent pending(Context ctx,String action,int code){
        Intent i=new Intent(ctx,ReminderReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx,code,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static void notifyUser(Context context,String title,String message){
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null)return;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"تذكيرات حساباتي",NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("متابعة أشغال الدعاية ومواعيد تسليمها والتذكير اليومي");
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
        }
        PendingIntent open=PendingIntent.getActivity(context,1,new Intent(context,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notif=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_stat_hisabati)
          .setContentTitle(title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
          .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).build();
        nm.notify((int)(System.currentTimeMillis()%100000),notif);
    }
    @Override public void onReceive(Context context,Intent intent){
        String action=intent==null?"":intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)){schedule(context);return;}
        if(TASK.equals(action)){
            SharedPreferences p=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
            if(p.getInt("unfinished",0)>0)
                notifyUser(context,"🖨️ متابعة أشغال الدعاية والزبائن",unfinishedDescription(p,false));
        }else if(DUE.equals(action)){
            SharedPreferences p=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
            if(p.getInt("unfinished",0)>0){
                notifyUser(context,"⏰ موعد تسليم شغل",unfinishedDescription(p,true));
                schedule(context); // schedule next distinct deadline after this one
            }
        }else if(DAILY.equals(action))notifyUser(context,"تذكير حساباتي","لا تنسَ تسجيل دخل اليوم ومصروفاته.");
    }
}
