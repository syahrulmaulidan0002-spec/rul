package com.gameboost.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** Adapter daftar game: game yang terdeteksi tampil paling atas dengan label GAME. */
final class GameAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<GameItem> data;
    String selectedPkg;

    GameAdapter(Context context, List<GameItem> data) {
        this.inflater = LayoutInflater.from(context);
        this.data = data;
    }

    @Override public int getCount() { return data.size(); }

    @Override public Object getItem(int position) { return data.get(position); }

    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView : inflater.inflate(R.layout.item_game, parent, false);
        GameItem item = data.get(position);
        boolean selected = item.pkg.equals(selectedPkg);

        ((ImageView) v.findViewById(R.id.gameIcon)).setImageDrawable(item.icon);
        ((TextView) v.findViewById(R.id.gameName)).setText(item.label);
        ((TextView) v.findViewById(R.id.gamePkg)).setText(item.pkg);
        v.findViewById(R.id.gameTag).setVisibility(item.isGame ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.gameCheck).setVisibility(selected ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.gameBox).setBackgroundResource(
                selected ? R.drawable.bg_game_item_sel : R.drawable.bg_game_item);
        return v;
    }
}
