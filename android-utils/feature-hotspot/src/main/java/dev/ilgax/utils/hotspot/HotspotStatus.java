package dev.ilgax.utils.hotspot;

import android.os.Parcel;
import android.os.Parcelable;

/** Snapshot of the Wi-Fi hotspot returned by the UserService. Plain data; mirrors the CLI's summary. */
public final class HotspotStatus implements Parcelable {
    /** WifiManager WIFI_AP_STATE_*: 10 disabling, 11 disabled, 12 enabling, 13 enabled, 14 failed, -1 unknown. */
    public int apState = -1;
    public String ssid;
    public boolean hidden;
    /** SoftApConfiguration.SECURITY_TYPE_*, -1 if unreadable. 0 is an open network. */
    public int securityType = -1;
    /** Null unless requested and the network is not open. */
    public String passphrase;
    /** Bit mask: 1 = 2.4 GHz, 2 = 5 GHz, 4 = 6 GHz, 8 = 60 GHz; 0 if unknown. */
    public int bands;
    /** Connected devices, -1 if unknown. */
    public int clientCount = -1;
    /** SSID of the Wi-Fi network being shared; null if not connected or unknown. */
    public String upstreamSsid;
    /** MHz of the upstream network; 0 = unknown/not connected, -1 = Wi-Fi is switched off. */
    public int upstreamFrequency;
    /** Null on success. TETHER_ERROR_* name, or "stage: Exception: message" for local failures. */
    public String errorName;
    public int errorCode;
    /** Verbose log of the call, with the passphrase scrubbed. */
    public String diagnostics = "";

    public HotspotStatus() {
    }

    private HotspotStatus(Parcel in) {
        apState = in.readInt();
        ssid = in.readString();
        hidden = in.readInt() != 0;
        securityType = in.readInt();
        passphrase = in.readString();
        bands = in.readInt();
        clientCount = in.readInt();
        upstreamSsid = in.readString();
        upstreamFrequency = in.readInt();
        errorName = in.readString();
        errorCode = in.readInt();
        diagnostics = in.readString();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(apState);
        out.writeString(ssid);
        out.writeInt(hidden ? 1 : 0);
        out.writeInt(securityType);
        out.writeString(passphrase);
        out.writeInt(bands);
        out.writeInt(clientCount);
        out.writeString(upstreamSsid);
        out.writeInt(upstreamFrequency);
        out.writeString(errorName);
        out.writeInt(errorCode);
        out.writeString(diagnostics);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<HotspotStatus> CREATOR = new Creator<HotspotStatus>() {
        @Override
        public HotspotStatus createFromParcel(Parcel in) {
            return new HotspotStatus(in);
        }

        @Override
        public HotspotStatus[] newArray(int size) {
            return new HotspotStatus[size];
        }
    };
}
