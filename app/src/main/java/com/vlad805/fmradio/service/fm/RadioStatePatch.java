package com.vlad805.fmradio.service.fm;

import androidx.annotation.Nullable;

import com.vlad805.fmradio.models.RtPlusInfo;

public final class RadioStatePatch {
    private Integer frequency;
    private String ps;
    private String rt;
    private String pi;
    private String country;
    private Integer pty;
    private int[] af;
    private Boolean stereo;
    private Integer rmssi;
    private Integer sinr;
    private String rtPlusTitle;
    private String rtPlusArtist;
    private String rtPlusAlbum;

    public Integer getFrequency() {
        return frequency;
    }

    public void setFrequency(final Integer frequency) {
        this.frequency = frequency;
    }

    public String getPs() {
        return ps;
    }

    public void setPs(final String ps) {
        this.ps = ps;
    }

    public String getRt() {
        return rt;
    }

    public void setRt(final String rt) {
        this.rt = rt;
    }

    public String getPi() {
        return pi;
    }

    public void setPi(final String pi) {
        this.pi = pi;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(final String country) {
        this.country = country;
    }

    public Integer getPty() {
        return pty;
    }

    public void setPty(final Integer pty) {
        this.pty = pty;
    }

    public int[] getAf() {
        return af;
    }

    public void setAf(final int[] af) {
        this.af = af;
    }

    public Boolean getStereo() {
        return stereo;
    }

    public void setStereo(final Boolean stereo) {
        this.stereo = stereo;
    }

    public Integer getRmssi() {
        return rmssi;
    }

    public void setRmssi(final Integer rmssi) {
        this.rmssi = rmssi;
    }

    public Integer getSinr() {
        return sinr;
    }

    public void setSinr(final Integer sinr) {
        this.sinr = sinr;
    }

    @Nullable
    public RtPlusInfo getRtPlus() {
        return hasRtPlus() ? new RtPlusInfo(rtPlusTitle, rtPlusArtist, rtPlusAlbum) : null;
    }

    public void setRtPlus(@Nullable final RtPlusInfo rtPlus) {
        if (rtPlus == null) {
            this.rtPlusTitle = null;
            this.rtPlusArtist = null;
            this.rtPlusAlbum = null;
        } else {
            this.rtPlusTitle = rtPlus.getTitle();
            this.rtPlusArtist = rtPlus.getArtist();
            this.rtPlusAlbum = rtPlus.getAlbum();
        }
    }

    public boolean hasRtPlus() {
        return rtPlusTitle != null || rtPlusArtist != null || rtPlusAlbum != null;
    }

    @Nullable
    public String getRtPlusTitle() {
        return rtPlusTitle;
    }

    public void setRtPlusTitle(@Nullable final String rtPlusTitle) {
        this.rtPlusTitle = rtPlusTitle;
    }

    @Nullable
    public String getRtPlusArtist() {
        return rtPlusArtist;
    }

    public void setRtPlusArtist(@Nullable final String rtPlusArtist) {
        this.rtPlusArtist = rtPlusArtist;
    }

    @Nullable
    public String getRtPlusAlbum() {
        return rtPlusAlbum;
    }

    public void setRtPlusAlbum(@Nullable final String rtPlusAlbum) {
        this.rtPlusAlbum = rtPlusAlbum;
    }

    public boolean isEmpty() {
        return (
                frequency == null &&
                ps == null &&
                rt == null &&
                pi == null &&
                country == null &&
                pty == null &&
                af == null &&
                stereo == null &&
                rmssi == null &&
                sinr == null &&
                !hasRtPlus()
        );
    }
}
