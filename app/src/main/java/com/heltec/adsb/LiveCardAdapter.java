package com.heltec.adsb;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Horizontal swipeable deck adapter for the main Home card(s).
 * Shows all currently LIVE aircraft (swipeable) or falls back to the latest spotted / standby card.
 */
public class LiveCardAdapter extends RecyclerView.Adapter<LiveCardAdapter.LiveCardViewHolder> {

    private final List<FlightRecord> items = new ArrayList<>();
    private final FlightAdapter.OnFlightClickListener listener;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateTimeFmt = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

    public LiveCardAdapter(FlightAdapter.OnFlightClickListener listener) {
        this.listener = listener;
    }

    public void submitCards(List<FlightRecord> cards) {
        items.clear();
        if (cards != null && !cards.isEmpty()) {
            items.addAll(cards);
        } else {
            // Single placeholder standby card
            items.add(null);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LiveCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_live_card, parent, false);
        return new LiveCardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LiveCardViewHolder holder, int position) {
        FlightRecord flight = items.get(position);
        Context ctx = holder.itemView.getContext();

        if (flight == null) {
            holder.iconBg.setBackgroundResource(R.drawable.bg_circle_blue_soft);
            holder.ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_blue)));
            holder.tvCallsign.setText("Listening...");
            holder.tvSubtitle.setText("Tap Bluetooth icon above to connect");
            holder.tvBadge.setText("STANDBY");
            holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.rev_text_secondary));
            holder.tvBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_surface_secondary)));
            holder.tvAlt.setText("-- ft");
            holder.tvMaxAlt.setText("-- ft");
            holder.tvSpeed.setText("-- kt");
            holder.tvIcao.setText("--");
            holder.tvPackets.setText("0");
            holder.tvSeen.setText("--");
            holder.tvLocation.setText("Waiting for ADS-B signals");
            holder.itemView.setOnClickListener(null);
            return;
        }

        long now = System.currentTimeMillis();
        boolean isLive = flight.isLive(now);

        AircraftDbHelper.AircraftInfo acInfo = AircraftDbHelper.getInstance(ctx).lookup(flight.icao);
        boolean isIcaoOnly = flight.callsign == null || flight.callsign.isEmpty()
                || flight.callsign.equalsIgnoreCase(flight.icao)
                || ModeSDecoder.isValidIcao(flight.callsign);
        String model = (acInfo != null) ? acInfo.displayModel() : "";
        String displayTitle = (isIcaoOnly && !model.isEmpty()) ? model : flight.callsign;
        holder.tvCallsign.setText(displayTitle);

        String airline = FlightEnricher.lookupAirline(flight.callsign);
        if (airline.isEmpty() && acInfo != null && !acInfo.operator.isEmpty()) {
            airline = acInfo.operator;
        }
        String country = FlightEnricher.lookupCountry(flight.icao);

        StringBuilder sub = new StringBuilder();
        if (!model.isEmpty() && !displayTitle.equals(model)) sub.append(model);
        if (!airline.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(airline);
        } else if (!country.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(country);
        }
        if (acInfo != null && !acInfo.reg.isEmpty()) {
            if (sub.length() > 0) sub.append(" (").append(acInfo.reg).append(")");
            else sub.append(acInfo.reg);
        }
        if (sub.length() == 0) {
            sub.append(isLive ? "Live ADS-B aircraft" : "Last spotted aircraft");
        }
        holder.tvSubtitle.setText(sub.toString());

        if (isLive) {
            holder.iconBg.setBackgroundResource(R.drawable.bg_circle_green);
            holder.ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_green)));
            holder.tvBadge.setText(items.size() > 1
                    ? String.format(Locale.getDefault(), "LIVE %d/%d", position + 1, items.size())
                    : "LIVE");
            holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.rev_green));
            holder.tvBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_green_soft)));
        } else {
            holder.iconBg.setBackgroundResource(R.drawable.bg_circle_blue_soft);
            holder.ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_blue)));
            holder.tvBadge.setText("LAST");
            holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.rev_text_secondary));
            holder.tvBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_surface_secondary)));
        }

        holder.tvAlt.setText(flight.lastAltitude >= 0
                ? String.format(Locale.getDefault(), "%,d ft", flight.lastAltitude)
                : "-- ft");
        int maxAlt = Math.max(flight.lastAltitude, flight.maxAltitude);
        holder.tvMaxAlt.setText(maxAlt >= 0
                ? String.format(Locale.getDefault(), "%,d ft", maxAlt)
                : "-- ft");
        holder.tvSpeed.setText(flight.lastSpeed >= 0
                ? String.format(Locale.getDefault(), "%d kt", flight.lastSpeed)
                : "-- kt");
        holder.tvIcao.setText(flight.icao != null && !flight.icao.isEmpty() ? flight.icao : "--");
        holder.tvPackets.setText(String.valueOf(flight.packetCount));

        long ageSec = Math.max(0L, (now - flight.lastSeenAt) / 1000L);
        if (isLive) {
            holder.tvSeen.setText(ageSec < 60 ? (ageSec + "s ago") : ((ageSec / 60) + "m ago"));
        } else {
            Date d = new Date(flight.lastSeenAt);
            holder.tvSeen.setText(DateUtils.isToday(flight.lastSeenAt)
                    ? timeFmt.format(d)
                    : dateTimeFmt.format(d));
        }

        if (flight.hasCoords()) {
            String place = FlightEnricher.findNearestPlace(flight.lastLat, flight.lastLon);
            if (!place.isEmpty()) {
                holder.tvLocation.setText(String.format(Locale.US, "%s (%.2f°, %.2f°)",
                        place, flight.lastLat, flight.lastLon));
            } else {
                holder.tvLocation.setText(String.format(Locale.US, "%.4f°, %.4f°",
                        flight.lastLat, flight.lastLon));
            }
        } else {
            holder.tvLocation.setText("No GPS fix yet");
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFlightClick(flight);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class LiveCardViewHolder extends RecyclerView.ViewHolder {
        final View iconBg;
        final ImageView ivIcon;
        final TextView tvCallsign;
        final TextView tvSubtitle;
        final TextView tvBadge;
        final TextView tvAlt;
        final TextView tvMaxAlt;
        final TextView tvSpeed;
        final TextView tvIcao;
        final TextView tvPackets;
        final TextView tvSeen;
        final TextView tvLocation;

        LiveCardViewHolder(@NonNull View itemView) {
            super(itemView);
            iconBg = itemView.findViewById(R.id.liveCardIconBg);
            ivIcon = itemView.findViewById(R.id.ivLiveCardIcon);
            tvCallsign = itemView.findViewById(R.id.tvLiveCardCallsign);
            tvSubtitle = itemView.findViewById(R.id.tvLiveCardSubtitle);
            tvBadge = itemView.findViewById(R.id.tvLiveCardBadge);
            tvAlt = itemView.findViewById(R.id.tvLiveCardAlt);
            tvMaxAlt = itemView.findViewById(R.id.tvLiveCardMaxAlt);
            tvSpeed = itemView.findViewById(R.id.tvLiveCardSpeed);
            tvIcao = itemView.findViewById(R.id.tvLiveCardIcao);
            tvPackets = itemView.findViewById(R.id.tvLiveCardPackets);
            tvSeen = itemView.findViewById(R.id.tvLiveCardSeen);
            tvLocation = itemView.findViewById(R.id.tvLiveCardLocation);
        }
    }
}
