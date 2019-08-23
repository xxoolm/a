package com.kunfei.bookshelf.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hwangjr.rxbus.RxBus;
import com.kunfei.basemvplib.BitIntentDataManager;
import com.kunfei.bookshelf.MApplication;
import com.kunfei.bookshelf.R;
import com.kunfei.bookshelf.bean.BookSourceBean;
import com.kunfei.bookshelf.bean.RecommendIndexBean;
import com.kunfei.bookshelf.constant.RxBusTag;
import com.kunfei.bookshelf.model.task.CheckSourceTask;
import com.kunfei.bookshelf.model.task.UpdateBookListTask;
import com.kunfei.bookshelf.view.activity.BookSourceActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.Scheduler;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static com.kunfei.bookshelf.constant.AppConstant.ActionDoneService;
import static com.kunfei.bookshelf.constant.AppConstant.ActionStartService;

public class UpdateBookListService extends Service {
    private static final int notificationId = 3339;
    private static final String ActionOpenActivity = "openActivity";

    private List<RecommendIndexBean> recommendIndexBeanList;
    private int threadsNum;
    private int checkIndex;
    private CompositeDisposable compositeDisposable;
    private ExecutorService executorService;
    private Scheduler scheduler;
    private UpdateBookListListener updateBookListListener;

    /**
     * 启动服务
     */
    public static void start(Context context, List<RecommendIndexBean> recommendIndexBeanList) {
        if (recommendIndexBeanList.isEmpty()) return;
        String key = String.valueOf(System.currentTimeMillis());
        BitIntentDataManager.getInstance().putData(key, recommendIndexBeanList);
        Intent intent = new Intent(context, UpdateBookListService.class);
        intent.putExtra("data_key", key);
        intent.setAction(ActionStartService);
        context.startService(intent);
    }

    /**
     * 停止服务
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, UpdateBookListService.class);
        intent.setAction(ActionDoneService);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preference = MApplication.getConfigPreferences();
        updateBookListListener = new UpdateBookListListener() {
            @Override
            public void nextCheck() {
                UpdateBookListService.this.nextCheck();
            }

            @Override
            public void compositeDisposableAdd(Disposable disposable) {
                compositeDisposable.add(disposable);
            }

            @Override
            public int getCheckIndex() {
                return checkIndex;
            }
        };
        threadsNum = preference.getInt(this.getString(R.string.pk_threads_num), 6);
        executorService = Executors.newFixedThreadPool(threadsNum);
        scheduler = Schedulers.from(executorService);
        compositeDisposable = new CompositeDisposable();
        updateNotification(0, "正在加载");
    }

    @SuppressWarnings("unchecked")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ActionDoneService:
                        doneService();
                        break;
                    case ActionStartService:
                        String key = intent.getStringExtra("data_key");
                        recommendIndexBeanList = (List<RecommendIndexBean>) BitIntentDataManager.getInstance().getData(key);
                        startCheck();
                }
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void doneService() {
        RxBus.get().post(RxBusTag.CHECK_SOURCE_FINISH, "校验完成");
        compositeDisposable.dispose();
        stopSelf();
    }

    /**
     * 更新通知
     */
    private void updateNotification(int state, String msg) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MApplication.channelIdReadAloud)
                .setSmallIcon(R.drawable.ic_network_check)
                .setOngoing(true)
                .setContentTitle(getString(R.string.check_book_source))
                .setContentText(msg)
                .setContentIntent(getActivityPendingIntent());
        builder.addAction(R.drawable.ic_stop_black_24dp, getString(R.string.cancel), getThisServicePendingIntent());
        if (recommendIndexBeanList != null) {
            builder.setProgress(recommendIndexBeanList.size(), state, false);
        }
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        Notification notification = builder.build();
        startForeground(notificationId, notification);
    }

    private PendingIntent getActivityPendingIntent() {
        Intent intent = new Intent(this, BookSourceActivity.class);
        intent.setAction(UpdateBookListService.ActionOpenActivity);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getThisServicePendingIntent() {
        Intent intent = new Intent(this, this.getClass());
        intent.setAction(ActionDoneService);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void startCheck() {
        if (recommendIndexBeanList != null && recommendIndexBeanList.size() > 0) {
            RxBus.get().post(RxBusTag.CHECK_SOURCE_STATE, "开始效验");
            checkIndex = -1;
            for (int i = 1; i <= threadsNum; i++) {
                nextCheck();
            }
        }
    }

    private synchronized void nextCheck() {
        checkIndex++;
        if (checkIndex > threadsNum) {
            String msg = String.format(getString(R.string.progress_show), checkIndex - threadsNum, recommendIndexBeanList.size());
            RxBus.get().post(RxBusTag.CHECK_SOURCE_STATE, msg);
            updateNotification(checkIndex - threadsNum, msg);
        }

        if (checkIndex < recommendIndexBeanList.size()) {
            UpdateBookListTask checkSource = new UpdateBookListTask(recommendIndexBeanList.get(checkIndex), scheduler, updateBookListListener);
            checkSource.startCheck();
        } else {
            if (checkIndex >= recommendIndexBeanList.size() + threadsNum - 1) {
                doneService();
            }
        }
    }

    public interface UpdateBookListListener {
        void nextCheck();

        void compositeDisposableAdd(Disposable disposable);

        int getCheckIndex();
    }

}