package com.azha.hisabati;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.print.PrintManager;
import android.print.PrintAttributes;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String URL="https://appassets.androidplatform.net/assets/index.html";
    private static final int SAVE_BACKUP=4101, LOAD_BACKUP=4102, PICK_IMAGE=4103, NOTIFICATION_PERMISSION=4104, SAVE_REPORT=4105;
    private static final int MAX_JSON=7*1024*1024;
    private WebView webView;
    private ValueCallback<Uri[]> imageCallback;
    private String pendingBackup;
    private String pendingReportFile;
    private WebView printWebView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11,11,13));
        getWindow().setNavigationBarColor(Color.rgb(11,11,13));
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder()
          .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView=new WebView(this);
        webView.setBackgroundColor(Color.rgb(11,11,13));
        WebSettings settings=webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Needed only for images explicitly selected through the Android picker.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);
        webView.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public void onPageFinished(WebView view,String url){
                if(getIntent()!=null && getIntent().getBooleanExtra("open_report",false)){
                    webView.evaluateJavascript(reportLaunchScript(getIntent()),null);
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                return !request.getUrl().toString().equals(URL);
            }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(imageCallback!=null)imageCallback.onReceiveValue(null);
                imageCallback=callback;
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                try{startActivityForResult(intent,PICK_IMAGE);}catch(Exception e){imageCallback.onReceiveValue(null);imageCallback=null;return false;}
                return true;
            }
        });
        // The JS bridge is exposed only to our bundled offline asset. Remote navigation is blocked.
        webView.addJavascriptInterface(new Bridge(),"HisabatiAndroid");
        setContentView(webView);
        webView.loadUrl(URL);
    }

    private final class Bridge {
        @JavascriptInterface public void exportBackup(String json){
            runOnUiThread(()->{
                if(json==null || json.length()>MAX_JSON){toast("نسخة البيانات أكبر من الحجم المسموح");return;}
                pendingBackup=json;
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE,"hisabati-azha-backup.json");
                try{startActivityForResult(i,SAVE_BACKUP);}catch(Exception e){toast("لا يمكن فتح شاشة حفظ النسخة");}
            });
        }
        @JavascriptInterface public void importBackup(){
            runOnUiThread(()->{
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});
                try{startActivityForResult(i,LOAD_BACKUP);}catch(Exception e){toast("لا يمكن فتح ملف النسخة الاحتياطية");}
            });
        }
        @JavascriptInterface public void configureReminders(int hours,String time,int unfinished){
            runOnUiThread(()->{
                ReminderReceiver.configure(MainActivity.this,hours,time,unfinished);
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);
                }
            });
        }
        @JavascriptInterface public void syncReports(String json){
            if(json==null || json.length()>500_000)return;
            try{new JSONObject(json);ReportReceiver.saveSnapshot(MainActivity.this,json);}catch(Exception ignored){}
        }
        @JavascriptInterface public void configureReports(String time,boolean daily,boolean monthly){
            runOnUiThread(()->{
                ReportReceiver.configure(MainActivity.this,time,daily,monthly);
                if((daily||monthly) && Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);
                }
            });
        }
        @JavascriptInterface public void clearReports(){
            runOnUiThread(()->ReportReceiver.clear(MainActivity.this));
        }
        @JavascriptInterface public void saveReportFile(String data,String fileName,String mime){
            if(data==null||data.length()>8_000_000)return;
            runOnUiThread(()->{
                pendingReportFile=data;
                String safeName=fileName!=null&&fileName.matches("[A-Za-z0-9._-]{1,120}")?fileName:"hisabati-report.csv";
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");
                i.putExtra(Intent.EXTRA_TITLE,safeName);
                try{startActivityForResult(i,SAVE_REPORT);}catch(Exception e){pendingReportFile=null;toast("تعذر فتح حفظ Excel");}
            });
        }
        @JavascriptInterface public void printReport(String html,String reportName){
            if(html==null || html.length()>3_000_000)return;
            runOnUiThread(()->{
                if(printWebView!=null){printWebView.destroy();printWebView=null;}
                printWebView=new WebView(MainActivity.this);
                printWebView.getSettings().setJavaScriptEnabled(false);
                final WebView pv=printWebView;
                pv.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView v,String url){
                        try{
                            PrintManager manager=(PrintManager)getSystemService(Context.PRINT_SERVICE);
                            if(manager==null){toast("الطباعة غير متوفرة");return;}
                            String safe=reportName!=null&&reportName.matches("[A-Za-z0-9._-]{1,120}")?reportName:"hisabati-report";
                            manager.print(safe,pv.createPrintDocumentAdapter(safe),new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                            toast("اختر حفظ كملف PDF من خيارات الطباعة");
                        }catch(Exception e){toast("تعذر تحضير تقرير PDF");}
                    }
                });
                pv.loadDataWithBaseURL("https://appassets.androidplatform.net/",html,"text/html","UTF-8",null);
            });
        }
        @JavascriptInterface public void clearReminders(){
            runOnUiThread(()->ReminderReceiver.clear(MainActivity.this));
        }
        @JavascriptInterface public void openDialer(String phone){
            if(phone==null || !phone.matches("\\+?[0-9]{3,20}"))return;
            runOnUiThread(()->{
                try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone)));}
                catch(Exception e){toast("تعذّر فتح تطبيق الاتصال");}
            });
        }
        @JavascriptInterface public void testNotification(){
            runOnUiThread(()->{
                ReminderReceiver.notifyUser(MainActivity.this,"حساباتي","تجربة إشعارات حساباتي");
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);
                }
            });
        }
    }

    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code==PICK_IMAGE){
            if(imageCallback!=null){imageCallback.onReceiveValue(result==RESULT_OK&&data!=null&&data.getData()!=null?new Uri[]{data.getData()}:null);imageCallback=null;}
            return;
        }
        if(result!=RESULT_OK || data==null || data.getData()==null){
            if(code==SAVE_REPORT)pendingReportFile=null;
            if(code==SAVE_BACKUP)pendingBackup=null;
            return;
        }
        Uri uri=data.getData();
        if(code==SAVE_BACKUP){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IllegalStateException("output null");
                out.write(pendingBackup.getBytes(StandardCharsets.UTF_8));out.flush();
                toast("حُفظت النسخة الاحتياطية");
            }catch(Exception e){toast("تعذّر حفظ النسخة");}
            finally{pendingBackup=null;}
        }else if(code==SAVE_REPORT){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IllegalStateException("output null");
                out.write(pendingReportFile.getBytes(StandardCharsets.UTF_8));out.flush();
                toast("تم حفظ ملف التقرير، افتحه باستخدام Excel");
            }catch(Exception e){toast("تعذر حفظ التقرير");}
            finally{pendingReportFile=null;}
        }else if(code==LOAD_BACKUP){
            try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in==null)throw new IllegalStateException("input null");
                byte[] chunk=new byte[8192];int n;
                while((n=in.read(chunk))!=-1){if(out.size()+n>MAX_JSON)throw new IllegalStateException("الملف كبير جدًا");out.write(chunk,0,n);}
                String json=out.toString("UTF-8");
                webView.evaluateJavascript("window.receiveBackupFromNative("+JSONObject.quote(json)+")",null);
            }catch(Exception e){toast("تعذّر فتح النسخة الاحتياطية");}
        }
    }
    private String reportLaunchScript(Intent intent){
        String period=intent==null?"day":intent.getStringExtra("report_period");
        String key=intent==null?"":intent.getStringExtra("report_key");
        if(!"month".equals(period))period="day";
        if(key==null || !key.matches("[0-9]{4}-[0-9]{2}(-[0-9]{2})?"))key="";
        return "window.__openHisabatiReport={period:"+JSONObject.quote(period)+",key:"+JSONObject.quote(key)+"};";
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        if(intent!=null&&intent.getBooleanExtra("open_report",false)&&webView!=null){
            webView.evaluateJavascript(reportLaunchScript(intent)+"if(window.openNotifiedReport && !document.getElementById('app').classList.contains('hidden'))openNotifiedReport();",null);
        }
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){
        webView.evaluateJavascript("(function(){var visible=!document.getElementById('app').classList.contains('hidden');if(visible){lockApp();return 'locked';}return 'exit';})()", result->{
            if("\"exit\"".equals(result))finish();
        });
    }
    @Override protected void onDestroy(){
        if(imageCallback!=null){imageCallback.onReceiveValue(null);imageCallback=null;}
        if(webView!=null){webView.removeJavascriptInterface("HisabatiAndroid");webView.destroy();}
        if(printWebView!=null){printWebView.destroy();printWebView=null;}
        super.onDestroy();
    }
}
