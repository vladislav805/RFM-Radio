package com.vlad805.fmradio.service.fm;

public interface IFMController {
    boolean isInstalled();

    boolean isObsolete();

    void install();

    void launch();

    void applyPreference(String key, String value);

    void prepareBinary();

    void kill();

    void enable();

    void setupTunerByPreferences(String[] changed);

    void disable();

    void setFrequency(int kHz);

    void jump(int direction);

    void hwSeek(int direction);

    void hwSearch();

    void setPowerMode(String mode);
}
