package io.github.mwarevn.fakegps.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.domain.model.FavoriteLocation

class FavListAdapter(
    ) : ListAdapter<FavoriteLocation,FavListAdapter.ViewHolder>(FavListComparetor()) {

    var onItemClick : ((FavoriteLocation) -> Unit)? = null
    var onItemDelete : ((FavoriteLocation) -> Unit)? = null

   inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {

        private val address: TextView = view.findViewById(R.id.address)
        private val delete: ImageView = itemView.findViewById(R.id.del)

        fun bind(favorite: FavoriteLocation){
            address.text = favorite.address
            delete.setOnClickListener {
                onItemDelete?.invoke(favorite)
            }
            address.setOnClickListener {
                onItemClick?.invoke(favorite)
            }
        }
    }

    class FavListComparetor : DiffUtil.ItemCallback<FavoriteLocation>() {
        override fun areItemsTheSame(oldItem: FavoriteLocation, newItem: FavoriteLocation): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteLocation, newItem: FavoriteLocation): Boolean {
            return oldItem == newItem
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fav_items, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null){
            holder.bind(item)

        }

    }

}