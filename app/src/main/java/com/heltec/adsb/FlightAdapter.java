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
 * RecyclerView adapter displaying detected ADS-B flights in the Flights tab.
 */
public class FlightAdapter extends RecyclerView.Adapter<FlightAdapter.FlightViewHolder> {

    public interface OnFlightClickListener {
        void onFlightClick(FlightRecord flight);
    }

    private final List<FlightRecord> items = new ArrayList<>();
    private final OnFlightClickListener listener;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateTimeFmt = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

    public FlightAdapter(OnFlightClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<FlightRecord> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FlightViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_flight, parent, false);
        return new FlightViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FlightViewHolder holder, int position) {
        FlightRecord item = items.get(position);
        Context ctx = holder.itemView.getContext();
        long now = System.currentTimeMillis();
        boolean isLive = item.isLive(now);

        AircraftDbHelper.AircraftInfo acInfo = AircraftDbHelper.getInstance(ctx).lookup(item.icao);
        boolean isIcaoOnly = item.callsign == null || item.callsign.isEmpty()
                || item.callsign.equalsIgnoreCase(item.icao)
                || ModeSDecoder.isValidIcao(item.callsign);
        String displayTitle = (isIcaoOnly && acInfo != null && !acInfo.displayModel().isEmpty())
                ? acInfo.displayModel()
                : item.callsign;
        holder.tvCallsign.setText(displayTitle);
        holder.tvLiveBadge.setVisibility(isLive ? View.VISIBLE : View.GONE);

        if (isLive) {
            holder.iconBg.setBackgroundResource(R.drawable.bg_circle_green);
            holder.ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_green)));
        } else {
            holder.iconBg.setBackgroundResource(R.drawable.bg_circle_blue_soft);
            holder.ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rev_blue)));
        }

        String airline = FlightEnricher.lookupAirline(item.callsign);
        if (airline.isEmpty() && acInfo != null && !acInfo.operator.isEmpty()) {
            airline = acInfo.operator;
        }
        String country = FlightEnricher.lookupCountry(item.icao);

        StringBuilder telemetry = new StringBuilder();
        if (!airline.isEmpty()) {
            telemetry.append(airline);
        } else if (!country.isEmpty()) {
            telemetry.append(country);
        }
        if (acInfo != null && !acInfo.type.isEmpty() && !displayTitle.equals(acInfo.type)) {
            if (telemetry.length() > 0) telemetry.append(" · ");
            telemetry.append(acInfo.type);
        }
        if (item.lastAltitude >= 0) {
            if (telemetry.length() > 0) telemetry.append(" · ");
            telemetry.append(String.format(Locale.getDefault(), "%,d ft", item.lastAltitude));
        }
        if (item.lastSpeed >= 0) {
            if (telemetry.length() > 0) telemetry.append(" · ");
            telemetry.append(String.format(Locale.getDefault(), "%d kt", item.lastSpeed));
        }
        if (item.icao != null && !item.icao.isEmpty() && !displayTitle.equalsIgnoreCase(item.icao)
                && (isIcaoOnly || (airline.isEmpty() && (acInfo == null || acInfo.type.isEmpty())))) {
            if (telemetry.length() > 0) telemetry.append(" · ");
            telemetry.append(item.icao);
        }
        holder.tvTelemetry.setText(telemetry.length() > 0 ? telemetry.toString() : "Telemetry pending");

        if (item.hasCoords()) {
            holder.tvCoords.setVisibility(View.VISIBLE);
            String place = FlightEnricher.findNearestPlace(item.lastLat, item.lastLon);
            if (!place.isEmpty()) {
                holder.tvCoords.setText(place);
            } else {
                holder.tvCoords.setText(String.format(Locale.US, "%.4f°, %.4f°", item.lastLat, item.lastLon));
            }
        } else {
            holder.tvCoords.setVisibility(View.GONE);
        }

        Date lastSeenDate = new Date(item.lastSeenAt);
        holder.tvTime.setText(DateUtils.isToday(item.lastSeenAt)
                ? timeFmt.format(lastSeenDate)
                : dateTimeFmt.format(lastSeenDate));
        holder.tvPackets.setText(String.format(Locale.getDefault(), "%d pkt", item.packetCount));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFlightClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class FlightViewHolder extends RecyclerView.ViewHolder {
        final View iconBg;
        final ImageView ivIcon;
        final TextView tvCallsign;
        final TextView tvLiveBadge;
        final TextView tvTelemetry;
        final TextView tvCoords;
        final TextView tvTime;
        final TextView tvPackets;

        FlightViewHolder(@NonNull View itemView) {
            super(itemView);
            iconBg = itemView.findViewById(R.id.flightIconBg);
            ivIcon = itemView.findViewById(R.id.ivFlightIcon);
            tvCallsign = itemView.findViewById(R.id.tvItemCallsign);
            tvLiveBadge = itemView.findViewById(R.id.tvItemLiveBadge);
            tvTelemetry = itemView.findViewById(R.id.tvItemTelemetry);
            tvCoords = itemView.findViewById(R.id.tvItemCoords);
            tvTime = itemView.findViewById(R.id.tvItemTime);
            tvPackets = itemView.findViewById(R.id.tvItemPackets);
        }
    }
}
