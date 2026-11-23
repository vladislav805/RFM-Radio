package com.vlad805.fmradio.controller;

import androidx.annotation.NonNull;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;

/**
 * In-process control surface exposed by {@link com.vlad805.fmradio.service.FMService} via its Local Binder.
 */
public interface RadioServiceController {
    @NonNull
    RadioState getState();

    void enable();

    void disable();

    void setFrequency(int kHz);

    void jump(@NonNull Direction direction);

    void hwSeek(@NonNull Direction direction);

    void hwSearch();

    void cancelHwSearch();

    void setPowerMode(@NonNull PowerMode mode);

    void toggleSpeaker();

    void startRecord();

    void stopRecord();

    void addStateListener(@NonNull StateListener listener);

    void removeStateListener(@NonNull StateListener listener);

    void addEventListener(@NonNull EventListener listener);

    void removeEventListener(@NonNull EventListener listener);
}
