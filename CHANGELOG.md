# Changelog

Note the first digit of every adapter version corresponds to the major version of the Chartboost Mediation SDK compatible with that adapter. 
Adapters are compatible with any Chartboost Mediation SDK version within that major version.

All official releases can be found on this repository's [releases page](https://github.com/ChartBoost/chartboost-mediation-android-adapter-amazon-publisher-services/releases).

## Table of Contents
- [Mediation 5](#mediation-5)
- [Mediation 4](#mediation-4)

## Mediation 5

### 5.9.10.2.0
- Add `bannerSize` parameter to `AmazonPublisherServicesAdapterPreBidRequest`.
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.2.
- This version of the adapter supports Chartboost Mediation SDK version 5.+.

## Mediation 4

### 4.9.10.2.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.2.

### 4.9.10.1.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.1.

### 4.9.10.0.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.0.

### 4.9.9.5.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.9.5.

### 4.9.9.3.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.9.3.

### 4.9.9.2.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.9.2.

### 4.9.8.10.1
- If a `PreBiddingListener` is set before initialization, Amazon Publisher Services will not be initialized and the default `PreBiddingListener` will not be set.

### 4.9.10.2.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.2.

### 4.9.10.1.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.1.

### 4.9.10.0.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.10.0.

### 4.9.9.5.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.9.5.

### 4.9.9.3.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.9.3.

### 4.9.9.2.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.9.2.

### 4.9.8.10.1
- If a `PreBiddingListener` is set before initialization, Amazon Publisher Services will not be initialized and the default `PreBiddingListener` will not be set.

### 4.9.8.10.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.10.

### 4.9.8.9.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.9.
- Fix memory leaks that could occur when fullscreen ads are shown from an `Activity`.
- Chartboost is not permitted to wrap load or initialization calls for Amazon APS. Please review Amazon's documentation to initialize Amazon Publisher Services, implement a `PreBiddingListener`, and set it on `AmazonPublisherServicesAdapter.preBiddingListener`.

### 4.9.8.8.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.8.

### 4.9.8.6.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.6.

### 4.9.8.5.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.5.

### 4.9.8.4.2
- Updated to handle recent AdFormat changes.

### 4.9.8.4.1
- Guard against multiple continuation resumes.

### 4.9.8.4.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.4.

### 4.9.8.2.1
- Guard against multiple continuation resumes.

### 4.9.8.2.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.8.2.
- Re-align the `onShowSuccess()` lambda to Amazon's `onImpressionFired()` callback for reliable show results.

### 4.9.7.1.2
- Added ProGuard rules.

### 4.9.7.1.1
- Added support for rewarded ads.
- Ensured all callbacks to the Chartboost Mediation SDK occur on Main.

### 4.9.7.1.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.7.1.

### 4.9.7.0.1
- Updated the dependency on Chartboost Mediation SDK to 4.0.0.

### 4.9.7.0.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.7.0.

### 4.9.6.2.1
- Updated the dependency on Chartboost Mediation SDK to 4.0.0.

### 4.9.6.2.0
- This version of the adapter has been certified with Amazon Publisher Services SDK 9.6.2.
