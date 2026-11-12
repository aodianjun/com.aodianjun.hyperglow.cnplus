package com.eza.hyperglow.aod;

import android.os.Bundle;
import com.eza.hyperglow.aod.IAodLyricCallback;
import com.eza.hyperglow.aod.IAodSpectrumCallback;

interface IAodLyricBridge {
    oneway void registerCallback(IAodLyricCallback callback);
    oneway void unregisterCallback(IAodLyricCallback callback);
    oneway void registerSpectrumCallback(IAodSpectrumCallback callback);
    oneway void unregisterSpectrumCallback(IAodSpectrumCallback callback);
    oneway void reportCapabilities(in Bundle report);
}
