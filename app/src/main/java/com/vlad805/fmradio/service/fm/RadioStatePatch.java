package com.vlad805.fmradio.service.fm;

import android.content.Intent;

import com.vlad805.fmradio.C;

public final class RadioStatePatch {
    private Integer frequency;
    private String ps;
    private String rt;
    private String pi;
    private Integer pty;
    private int[] af;
    private Boolean stereo;

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

    public boolean isEmpty() {
        return frequency == null && ps == null && rt == null && pi == null && pty == null && af == null && stereo == null;
    }

    public Intent toIntent(final String action) {
        final Intent intent = new Intent(action);

        if (frequency != null) {
            intent.putExtra(C.Key.FREQUENCY, frequency);
        }

        if (ps != null) {
            intent.putExtra(C.Key.PS, ps);
        }

        if (rt != null) {
            intent.putExtra(C.Key.RT, rt);
        }

        if (pi != null) {
            intent.putExtra(C.Key.PI, pi);
        }

        if (pty != null) {
            intent.putExtra(C.Key.PTY, pty);
        }

        if (af != null) {
            intent.putExtra(C.Key.FREQUENCIES, af);
        }

        if (stereo != null) {
            intent.putExtra(C.Key.STEREO_MODE, stereo);
        }

        return intent;
    }
}
