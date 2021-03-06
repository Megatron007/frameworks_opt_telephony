/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.service.carrier.CarrierMessagingService;
import android.service.carrier.ICarrierMessagingCallback;
import android.service.carrier.ICarrierMessagingService;
import android.service.carrier.MessagePdu;
import android.telephony.CarrierMessagingServiceManager;
import android.telephony.Rlog;

import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Filters incoming SMS with carrier services.
 * <p> A new instance must be created for filtering each message.
 */
public class CarrierServicesSmsFilter {
    protected static final boolean DBG = true;

    private final Context mContext;
    private final int mPhoneId;
    private final int mSubId;
    private final byte[][] mPdus;
    private final int mDestPort;
    private final String mPduFormat;
    private final CarrierServicesSmsFilterCallbackInterface mCarrierServicesSmsFilterCallback;
    private final String mLogTag;

    CarrierServicesSmsFilter(
            Context context,
            int phoneId,
            int subId,
            byte[][] pdus,
            int destPort,
            String pduFormat,
            CarrierServicesSmsFilterCallbackInterface carrierServicesSmsFilterCallback,
            String logTag) {
        mContext = context;
        mPhoneId = phoneId;
        mSubId = subId;
        mPdus = pdus;
        mDestPort = destPort;
        mPduFormat = pduFormat;
        mCarrierServicesSmsFilterCallback = carrierServicesSmsFilterCallback;
        mLogTag = logTag;
    }

    /**
     * @return {@code true} if the SMS was handled by carrier services.
     */
    boolean filter() {
        List<String> carrierPackages = null;
        UiccCard card = UiccController.getInstance().getUiccCard(mPhoneId);
        if (card != null) {
            carrierPackages = card.getCarrierPackageNamesForIntent(
                    mContext.getPackageManager(),
                    new Intent(CarrierMessagingService.SERVICE_INTERFACE));
        } else {
            Rlog.e(mLogTag, "UiccCard not initialized.");
        }
        if (carrierPackages != null && carrierPackages.size() == 1) {
            log("Found carrier package.");
            filterWithCarrierApp(carrierPackages.get(0));
            return true;
        }

        // It is possible that carrier app is not present as a CarrierPackage, but instead as a
        // system app
        List<String> systemPackages =
                getSystemAppForIntent(new Intent(CarrierMessagingService.SERVICE_INTERFACE));

        if (systemPackages != null && systemPackages.size() == 1) {
            log("Found system package.");
            filterWithCarrierApp(systemPackages.get(0));
            return true;
        }
        logv("Unable to find carrier package: " + carrierPackages
                + ", nor systemPackages: " + systemPackages);
        return false;
    }

    private void filterWithCarrierApp(String carrierPackageName) {
        CarrierSmsFilter smsFilter = new CarrierSmsFilter(mPdus, mDestPort, mPduFormat);
        CarrierSmsFilterCallback smsFilterCallback =
                new CarrierSmsFilterCallback(mCarrierServicesSmsFilterCallback, smsFilter);
        smsFilter.filterSms(carrierPackageName, smsFilterCallback);
    }

    private List<String> getSystemAppForIntent(Intent intent) {
        List<String> packages = new ArrayList<String>();
        PackageManager packageManager = mContext.getPackageManager();
        List<ResolveInfo> receivers = packageManager.queryIntentServices(intent, 0);
        String carrierFilterSmsPerm = "android.permission.CARRIER_FILTER_SMS";

        for (ResolveInfo info : receivers) {
            if (info.serviceInfo == null) {
                loge("Can't get service information from " + info);
                continue;
            }
            String packageName = info.serviceInfo.packageName;
            if (packageManager.checkPermission(carrierFilterSmsPerm, packageName)
                    == packageManager.PERMISSION_GRANTED) {
                packages.add(packageName);
                if (DBG) log("getSystemAppForIntent: added package " + packageName);
            }
        }
        return packages;
    }

    private void log(String message) {
        Rlog.d(mLogTag, message);
    }

    private void loge(String message) {
        Rlog.e(mLogTag, message);
    }

    private void logv(String message) {
        Rlog.e(mLogTag, message);
    }

    /**
     * Result of filtering SMS is returned in this callback.
     */
    interface CarrierServicesSmsFilterCallbackInterface {
        void onFilterComplete(int result);
    }


    /**
     * Asynchronously binds to the carrier messaging service, and filters out the message if
     * instructed to do so by the carrier messaging service. A new instance must be used for every
     * message.
     */
    private final class CarrierSmsFilter extends CarrierMessagingServiceManager {
        private final byte[][] mPdus;
        private final int mDestPort;
        private final String mSmsFormat;
        // Instantiated in filterSms.
        private volatile CarrierSmsFilterCallback mSmsFilterCallback;

        CarrierSmsFilter(byte[][] pdus, int destPort, String smsFormat) {
            mPdus = pdus;
            mDestPort = destPort;
            mSmsFormat = smsFormat;
        }

        /**
         * Attempts to bind to a {@link ICarrierMessagingService}. Filtering is initiated
         * asynchronously once the service is ready using {@link #onServiceReady}.
         */
        void filterSms(String carrierPackageName, CarrierSmsFilterCallback smsFilterCallback) {
            mSmsFilterCallback = smsFilterCallback;
            if (!bindToCarrierMessagingService(mContext, carrierPackageName)) {
                loge("bindService() for carrier messaging service failed");
                smsFilterCallback.onFilterComplete(CarrierMessagingService.RECEIVE_OPTIONS_DEFAULT);
            } else {
                logv("bindService() for carrier messaging service succeeded");
            }
        }

        /**
         * Invokes the {@code carrierMessagingService} to filter messages. The filtering result is
         * delivered to {@code smsFilterCallback}.
         */
        @Override
        protected void onServiceReady(ICarrierMessagingService carrierMessagingService) {
            try {
                carrierMessagingService.filterSms(
                        new MessagePdu(Arrays.asList(mPdus)), mSmsFormat, mDestPort, mSubId,
                        mSmsFilterCallback);
            } catch (RemoteException e) {
                loge("Exception filtering the SMS: " + e);
                mSmsFilterCallback.onFilterComplete(
                        CarrierMessagingService.RECEIVE_OPTIONS_DEFAULT);
            }
        }
    }

    /**
     * A callback used to notify the platform of the carrier messaging app filtering result. Once
     * the result is ready, the carrier messaging service connection is disposed.
     */
    private final class CarrierSmsFilterCallback extends ICarrierMessagingCallback.Stub {
        private final CarrierServicesSmsFilterCallbackInterface mCarrierServicesSmsFilterCallback;
        private final CarrierMessagingServiceManager mCarrierMessagingServiceManager;

        CarrierSmsFilterCallback(
                CarrierServicesSmsFilterCallbackInterface carrierServicesSmsFilterCallback,
                CarrierMessagingServiceManager carrierMessagingServiceManager) {
            mCarrierServicesSmsFilterCallback = carrierServicesSmsFilterCallback;
            mCarrierMessagingServiceManager = carrierMessagingServiceManager;
        }

        /**
         * This method should be called only once.
         */
        @Override
        public void onFilterComplete(int result) {
            mCarrierMessagingServiceManager.disposeConnection(mContext);
            // Calling identity was the CarrierMessagingService in this callback, change it back to
            // ours. This is required for dropSms() and VisualVoicemailSmsFilter.filter().
            long token = Binder.clearCallingIdentity();
            try {
                mCarrierServicesSmsFilterCallback.onFilterComplete(result);
            } finally {
                // return back to the CarrierMessagingService, restore the calling identity.
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onSendSmsComplete(int result, int messageRef) {
            loge("Unexpected onSendSmsComplete call with result: " + result);
        }

        @Override
        public void onSendMultipartSmsComplete(int result, int[] messageRefs) {
            loge("Unexpected onSendMultipartSmsComplete call with result: " + result);
        }

        @Override
        public void onSendMmsComplete(int result, byte[] sendConfPdu) {
            loge("Unexpected onSendMmsComplete call with result: " + result);
        }

        @Override
        public void onDownloadMmsComplete(int result) {
            loge("Unexpected onDownloadMmsComplete call with result: " + result);
        }
    }
}
