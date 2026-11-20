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
import android.os.Build;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.Locale;

/** Personal, offline, inexact summaries: no internet or external accounts. */
public final class ReportReceiver extends BroadcastReceiver {
    private static final String PREFS="hisabati_report_notifications";
    private static final String DAILY="com.azha.hisabati.SUMMARY_DAILY";
    private static final String MONTHLY="com.azha.hisabati.SUMMARY_MONTHLY";
    private static final String CHANNEL="hisabati_reports_v1";

    static void saveSnapshot(Context ctx,String json){
        if(json!=null && json.length()<=500_000)ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("cache",json).apply();
    }
    static void configure(Context ctx,String time,boolean daily,boolean monthly){
        if(!"23:00".equals(time)&&!"23:30".equals(time)&&!"00:00".equals(time))time="23:00";
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("time",time)
            .putBoolean("daily",daily).putBoolean("monthly",monthly).apply();
        schedule(ctx);
    }
    static void clear(Context ctx){
        AlarmManager a=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
        if(a!=null){a.cancel(pending(ctx,DAILY,21));a.cancel(pending(ctx,MONTHLY,22));}
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();
    }
    static PendingIntent pending(Context ctx,String action,int id){
        Intent intent=new Intent(ctx,ReportReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static void schedule(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        AlarmManager a=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);if(a==null)return;
        PendingIntent d=pending(ctx,DAILY,21),m=pending(ctx,MONTHLY,22);
        a.cancel(d);a.cancel(m);
        if(p.getBoolean("daily",false)){
            String[] hm=p.getString("time","23:00").split(":");
            Calendar next=Calendar.getInstance();
            next.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0]));next.set(Calendar.MINUTE,Integer.parseInt(hm[1]));
            next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);
            if(next.getTimeInMillis()<=System.currentTimeMillis())next.add(Calendar.DAY_OF_YEAR,1);
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.getTimeInMillis(),d);
        }
        if(p.getBoolean("monthly",false)){
            Calendar first=Calendar.getInstance();
            first.set(Calendar.DAY_OF_MONTH,1);first.set(Calendar.HOUR_OF_DAY,0);first.set(Calendar.MINUTE,10);
            first.set(Calendar.SECOND,0);first.set(Calendar.MILLISECOND,0);
            if(first.getTimeInMillis()<=System.currentTimeMillis())first.add(Calendar.MONTH,1);
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,first.getTimeInMillis(),m);
        }
    }
    private static String dateKey(Calendar c){return String.format(Locale.US,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
    private static String monthKey(Calendar c){return String.format(Locale.US,"%04d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1);}
    private static String amount(double v){return String.format(Locale.US,"%,.2f",v)+" د.ل";}
    private static void showReport(Context ctx,boolean monthly){
        if(Build.VERSION.SDK_INT>=33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        Calendar date=Calendar.getInstance();
        if(monthly)date.add(Calendar.MONTH,-1);
        else{
            String at=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("time","23:00");
            if("00:00".equals(at))date.add(Calendar.DAY_OF_YEAR,-1);
        }
        String key=monthly?monthKey(date):dateKey(date);
        JSONObject figures=new JSONObject();
        try{
            JSONObject all=new JSONObject(ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("cache","{}"));
            figures=all.optJSONObject(monthly?"months":"days")!=null?all.getJSONObject(monthly?"months":"days").optJSONObject(key):null;
        }catch(Exception ignore){}
        if(figures==null)figures=new JSONObject();
        double income=figures.optDouble("income",0),expenses=figures.optDouble("expense",0),net=figures.optDouble("net",0);
        double jobs=figures.optDouble("jobIncome",0),lent=figures.optDouble("lent",0),borrowed=figures.optDouble("borrowed",0);
        double paidToMe=figures.optDouble("lentPaid",0),paidByMe=figures.optDouble("borrowedPaid",0),netCash=figures.optDouble("netCash",0);
        String title=(monthly?"📅 الحساب الشهري — ":"📊 ملخص يوم حساباتي — ")+key;
        String note="الدخل المقبوض "+amount(income)+" | المصروفات "+amount(expenses)+" | صافي الحركة "+amount(net)+
            "\nتغير النقد شامل الديون "+amount(netCash)+" | من أشغال الزبائن "+amount(jobs)+" | ديون جديدة لك "+amount(lent)+" | ديون جديدة عليك "+amount(borrowed)+
            "\nأشغال جديدة "+figures.optInt("newJobsCount",0)+"، تم تسليم "+figures.optInt("deliveredJobsCount",0)+". سداد ديون لك "+amount(paidToMe)+" | سداد ديون عليك "+amount(paidByMe)+"\nاضغط لفتح التقرير المفصل وحفظ PDF أو Excel.";
        NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return;
        NotificationChannel ch=new NotificationChannel(CHANNEL,"ملخص حساباتي اليومي والشهري",NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("ملخصات الدخل والمصروفات والديون في حساباتي");ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(ch);
        Intent open=new Intent(ctx,MainActivity.class).putExtra("open_report",true).putExtra("report_period",monthly?"month":"day").putExtra("report_key",key)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent=PendingIntent.getActivity(ctx,monthly?101:100,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(ctx,CHANNEL).setSmallIcon(R.drawable.ic_stat_hisabati)
            .setContentTitle(title).setContentText("المقبوض "+amount(income)+" | المصروف "+amount(expenses)+" | الصافي "+amount(net))
            .setStyle(new Notification.BigTextStyle().bigText(note)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(intent).build();
        manager.notify(monthly?201:202,n);
    }
    @Override public void onReceive(Context ctx,Intent intent){
        String a=intent==null?"":intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(a)||Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a)){
            schedule(ctx);return;
        }
        if(DAILY.equals(a))showReport(ctx,false);
        if(MONTHLY.equals(a))showReport(ctx,true);
        schedule(ctx);
    }
}
