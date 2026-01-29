package com.yaswanth.offlinesos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SosMessage(
    val id: String,
    val senderPhone: String,
    val location: String,
    val time: String,
    val message: String,
    val distance: String = "Unknown distance",
    val isFinished: Boolean = false
)

class SosAdapter(
    private val alerts: MutableList<SosMessage>,
    private val onMarkFinished: (SosMessage) -> Unit
) : RecyclerView.Adapter<SosAdapter.SosViewHolder>() {

    class SosViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAlertTitle: TextView = view.findViewById(R.id.tvAlertTitle)
        val tvAlertTime: TextView = view.findViewById(R.id.tvAlertTime)
        val tvAlertMessage: TextView = view.findViewById(R.id.tvAlertMessage)
        val tvLat: TextView = view.findViewById(R.id.tvLat)
        val tvLon: TextView = view.findViewById(R.id.tvLon)
        val tvAlertDistance: TextView = view.findViewById(R.id.tvAlertDistance)
        val btnMarkFinished: Button = view.findViewById(R.id.btnMarkFinished)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SosViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sos_alert, parent, false)
        return SosViewHolder(view)
    }

    override fun onBindViewHolder(holder: SosViewHolder, position: Int) {
        val alert = alerts[position]
        
        holder.tvAlertTitle.text = if (alert.isFinished) "SOS Resolved" else "SOS Received!"
        holder.tvAlertTitle.setTextColor(
            holder.itemView.context.getColor(
                if (alert.isFinished) R.color.safety_green else R.color.sos_red
            )
        )
        
        holder.tvAlertTime.text = "Time: ${alert.time}"
        holder.tvAlertMessage.text = alert.message
        
        // Parse Location "lat,lon"
        val locParts = alert.location.split(",")
        var hasValidLoc = false
        if (locParts.size >= 2) {
             try {
                 val lat = locParts[0].toDouble()
                 val lon = locParts[1].toDouble()
                 // Check strict 0.0 only if it means unavailable in our system context (often 0,0 is default for missing)
                 if (lat != 0.0 || lon != 0.0) {
                     holder.tvLat.text = "Lat: %.4f".format(lat)
                     holder.tvLon.text = "Lon: %.4f".format(lon)
                     hasValidLoc = true
                 }
             } catch (e: NumberFormatException) {
                 // Invalid format
             }
        }

        if (!hasValidLoc) {
             holder.tvLat.text = "Lat: Unavailable" 
             holder.tvLon.text = "Lon: Unavailable"
             
             // Still distinct structure
             holder.tvLat.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
             holder.tvLon.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
        } else {
             holder.tvLat.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
             holder.tvLon.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
        }

        if (hasValidLoc) {

             holder.itemView.setOnClickListener {
                 try {
                     val lat = locParts[0].toDouble()
                     val lon = locParts[1].toDouble()
                     val intent = android.content.Intent(holder.itemView.context, TrackingActivity::class.java)
                     intent.putExtra("LAT", lat)
                     intent.putExtra("LON", lon)
                     holder.itemView.context.startActivity(intent)
                 } catch (e: Exception) {
                     android.widget.Toast.makeText(holder.itemView.context, "Invalid Coordinates", android.widget.Toast.LENGTH_SHORT).show()
                 }
             }
        } else {
             holder.itemView.setOnClickListener {
                 android.widget.Toast.makeText(holder.itemView.context, "Location unavailable", android.widget.Toast.LENGTH_SHORT).show()
             }
        }
        
        holder.tvAlertDistance.text = "Distance: ${alert.distance}"
        
        if (alert.isFinished) {
            holder.btnMarkFinished.visibility = View.GONE
        } else {
            holder.btnMarkFinished.visibility = View.VISIBLE
            holder.btnMarkFinished.setOnClickListener {
                onMarkFinished(alert)
            }
        }
    }

    override fun getItemCount() = alerts.size

    fun addAlert(alert: SosMessage) {
        alerts.add(0, alert) // Add to top
        notifyItemInserted(0)
    }
    
    fun markFinished(alert: SosMessage) {
        val index = alerts.indexOf(alert)
        if (index != -1) {
            val updated = alert.copy(isFinished = true)
            alerts[index] = updated
            notifyItemChanged(index)
        }
    }

    fun removeAlert(alert: SosMessage) {
        val index = alerts.indexOf(alert)
        if (index != -1) {
            alerts.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}
