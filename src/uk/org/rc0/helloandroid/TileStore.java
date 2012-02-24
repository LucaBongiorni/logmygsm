package uk.org.rc0.helloandroid;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;
import java.io.File;

public class TileStore {

  static final private int bm_log_size = 8;
  static final private int bm_size = 1<<bm_log_size;

  static final private String TAG = "TileStore";

  // -----------
  // State

  private static class Entry {
    int zoom;
    int pixel_shift;
    int tile_shift;
    int map_source;
    int x;
    int y;
    int cycle;
    // index in Trail's recent list of the last point we plotted, -1 if none
    int lx, ly;
    // index in Trail's recent list of the next point to try
    int n_next;
    Bitmap b;

    Bitmap getBitmap() {
      return b;
    }

    Entry(int azoom, int amap_source, int ax, int ay, Bitmap ab) {
      zoom = azoom;
      pixel_shift = (Merc28.shift - (zoom + bm_log_size));
      tile_shift = (Merc28.shift - zoom);
      map_source = amap_source;
      x = ax;
      y = ay;
      b = ab;
      n_next = 0;
      lx = -256;
      ly = -256;
      touch();
    }

    boolean isMatch(int azoom, int amap_source, int ax, int ay) {
      if ((azoom == zoom) &&
          (amap_source == map_source) &&
          (ax == x) &&
          (ay == y)) {
        return true;
      } else {
        return false;
      }
    }

    void touch () {
      cycle = draw_cycle;
    }

    void add_recent_trail() {
      Canvas my_canv = new Canvas(b);
      int [] result =
        Logger.mTrail.draw_recent_trail(my_canv,
            x<<tile_shift, y<<tile_shift,
            pixel_shift,
            lx, ly, n_next);
      n_next = result[2];
      lx = result[0];
      ly = result[1];
    }
  }

  // Eventually, make this depend on the canvas size and hence on how much welly the phone has
  static private final int SIZE = 32;

  static private Entry [] front;
  static private int next;
  static private Entry [] back;
  static int draw_cycle;

  static private Paint gray_paint;
  static Paint trail_paint;

  // -----------

  static void init () {
    front = new Entry[SIZE];
    next = 0;
    back = null;

    draw_cycle = 0;

    gray_paint = new Paint();
    gray_paint.setColor(Color.GRAY);
    trail_paint = new Paint();
    trail_paint.setColor(Color.argb(128, 0x8d, 0, 0xcf));
    trail_paint.setStyle(Paint.Style.FILL);
  }

  // -----------
  // Internal
  //

  static private void render_old_trail(Bitmap bm, int zoom, int tile_x, int tile_y) {
    int pixel_shift = (Merc28.shift - (zoom + bm_log_size));
    int tile_shift = (Merc28.shift - zoom);
    int xnw = tile_x << tile_shift;
    int ynw = tile_y << tile_shift;
    Canvas my_canv = new Canvas(bm);
    Trail.PointArray pa = Logger.mTrail.get_historical();
    int last_x = 0, last_y = 0;
    for (int i = 0; i < pa.n; i++) {
      int px = (pa.x[i] - xnw) >> pixel_shift;
      int py = (pa.y[i] - ynw) >> pixel_shift;
      boolean do_add = true;
      if (i > 0) {
        int manhattan = Math.abs(px - last_x) + Math.abs(py - last_y);
        if (manhattan < Trail.splot_gap) {
          do_add = false;
        }
      }
      if (do_add) {
        my_canv.drawCircle((float)px, (float)py, Trail.splot_radius, trail_paint);
        last_x = px;
        last_y = py;
      }
    }
  }

  static private Bitmap render_bitmap(int zoom, int map_source, int x, int y) {
    String filename = null;
    switch (map_source) {
      case Map.MAP_2G:
        filename = String.format("/sdcard/Maverick/tiles/Custom 2/%d/%d/%d.png.tile",
            zoom, x, y);
        break;
      case Map.MAP_3G:
        filename = String.format("/sdcard/Maverick/tiles/Custom 3/%d/%d/%d.png.tile",
            zoom, x, y);
        break;
      case Map.MAP_MAPNIK:
        filename = String.format("/sdcard/Maverick/tiles/mapnik/%d/%d/%d.png.tile",
            zoom, x, y);
        break;
      case Map.MAP_OPEN_CYCLE:
        filename = String.format("/sdcard/Maverick/tiles/OSM Cycle Map/%d/%d/%d.png.tile",
            zoom, x, y);
        break;
      case Map.MAP_OS:
        filename = String.format("/sdcard/Maverick/tiles/Ordnance Survey Explorer Maps (UK)/%d/%d/%d.png.tile",
            zoom, x, y);
        break;
    }
    File file = new File(filename);
    Bitmap bm;
    if (file.exists()) {
      Bitmap temp_bm = BitmapFactory.decodeFile(filename);
      bm = temp_bm.copy(Bitmap.Config.ARGB_8888, true);
    } else {
      bm = Bitmap.createBitmap(bm_size, bm_size, Bitmap.Config.ARGB_8888);
      Canvas my_canv = new Canvas(bm);
      my_canv.drawRect(0, 0, bm_size, bm_size, gray_paint);
    }
    // TODO : Draw trail points into the bitmap
    render_old_trail(bm, zoom, x, y);
    return bm;
  }

  static private Entry make_entry(int zoom, int map_source, int x, int y, Bitmap b) {
    return new Entry(zoom, map_source, x, y, b);
  }

  static private Entry lookup(int zoom, int map_source, int x, int y) {
    // front should never be null
    for (int i=next-1; i>=0; i--) {
      if (front[i].isMatch(zoom, map_source, x, y)) {
        return front[i];
      }
    }
    // Miss.  Any space left?
    if (next == SIZE) {
      // No.  Dump old junk
      back = front;
      // Might consider garbage collection here?
      front = new Entry[SIZE];
      next = 0;
      //Log.i(TAG, "Flushed tile store");
    }

    // Search 'back' array for a match.  'back' is either null, or full
    if (back != null) {
      for (int i=SIZE-1; i>=0; i--) {
        if (back[i].isMatch(zoom, map_source, x, y)) {
          front[next++] = back[i];
          return back[i];
        }
      }
    }

    // OK, no match.  We have to build a new bitmap from file
    Bitmap b = render_bitmap(zoom, map_source, x, y);
    Entry e = make_entry(zoom, map_source, x, y, b); // auto touch
    front[next++] = e;
    return e;

  }

  static private void ripple() {
    // gravitate entries in 'front' towards the end that's checked first
    for (int i=1; i<next; i++) {
      if (front[i-1].cycle > front[i].cycle) {
        // swap two entries over
        Entry t = front[i];
        front[i] = front[i-1];
        front[i-1] = t;
      }
    }
  }

  // -----------
  // Interface with map

  static public void invalidate() {
    front = new Entry[SIZE];
    next = 0;
    back = null;
  }

  static public void draw(Canvas c, int w, int h, int zoom, int map_source, Merc28 midpoint) {
    int pixel_shift = (Merc28.shift - (zoom + bm_log_size));

    // Compute pixels from origin at this zoom level for top-left corner of canvas
    int px, py;
    px = (midpoint.X >> pixel_shift) - (w>>1);
    py = (midpoint.Y >> pixel_shift) - (h>>1);

    // Hence compute tile containing top-left corner of canvas
    int tx, ty;
    tx = px >> bm_log_size;
    ty = py >> bm_log_size;

    // top-left corner of the top-left tile, in pixels relative to top-left corner of canvas
    int ox, oy;
    ox = (tx << bm_log_size) - px;
    oy = (ty << bm_log_size) - py;

    // This is used in maintaining the cache so that the most recently used
    // entries are the ones hit first in the search.
    draw_cycle++;

    int i, j;
    i = 0;
    while (ox + (i<<bm_log_size) < w) {
      int xx = ox + (i<<bm_log_size);
      j = 0;
      while (oy + (j<<bm_log_size) < h) {
        int yy = oy + (j<<bm_log_size);
        Entry e = lookup(zoom, map_source, tx+i, ty+j);
        e.add_recent_trail();
        e.touch();
        Bitmap bm = e.getBitmap();
        Rect dest = new Rect(xx, yy, xx+bm_size, yy+bm_size);
        c.drawBitmap(bm, null, dest, null);
        j++;
      }
      i++;
    }

    ripple();

  }

}


// vim:et:sw=2:sts=2

