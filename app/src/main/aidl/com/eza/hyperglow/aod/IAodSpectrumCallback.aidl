package com.eza.hyperglow.aod;

import android.os.Bundle;

/**
 * Low-rate music-spectrum frames from the app process to SystemUI.
 *
 * This is deliberately a separate callback from IAodLyricCallback: spectrum frames replace each
 * other and never participate in the lyric revision/freshness/keepalive state machine.
 */
oneway interface IAodSpectrumCallback {
    void onSpectrum(in Bundle frame);
}
