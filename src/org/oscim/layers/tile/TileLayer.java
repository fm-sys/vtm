/*
 * Copyright 2013 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.layers.tile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import org.oscim.core.GeoPoint;
import org.oscim.core.MapPosition;
import org.oscim.database.IMapDatabase;
import org.oscim.database.IMapDatabase.OpenResult;
import org.oscim.database.MapDatabaseFactory;
import org.oscim.database.MapDatabases;
import org.oscim.database.MapInfo;
import org.oscim.database.MapOptions;
import org.oscim.layers.Layer;
import org.oscim.renderer.GLRenderer;
import org.oscim.theme.ExternalRenderTheme;
import org.oscim.theme.InternalRenderTheme;
import org.oscim.theme.RenderTheme;
import org.oscim.theme.RenderThemeHandler;
import org.oscim.theme.Theme;
import org.oscim.view.MapView;
import org.xml.sax.SAXException;

import android.util.Log;

public class TileLayer extends Layer {
	private final static String TAG = TileLayer.class.getName();

	private boolean mClearMap = true;

	private final TileManager mTileManager;

	private final JobQueue mJobQueue;
	// TODO use 1 download and 1 generator thread instead
		private final MapWorker mMapWorkers[];
		private final int mNumMapWorkers = 4;
		private final TileGenerator mTileGenerators[];

	public TileLayer(MapView mapView) {
		super(mapView);
		mTileManager = new TileManager(mapView, this);
		mJobQueue = new JobQueue();
		mMapWorkers = new MapWorker[mNumMapWorkers];
		mTileGenerators = new TileGenerator[mNumMapWorkers];

		TileGenerator.setDebugSettings(mapView.getDebugSettings());

		for (int i = 0; i < mNumMapWorkers; i++) {
			mTileGenerators[i] = new TileGenerator();
			mMapWorkers[i] = new MapWorker(i, mJobQueue, mTileGenerators[i], mTileManager);
			mMapWorkers[i].start();
		}

		mLayer = new TileRenderLayer(mapView, mTileManager);
	}

	public TileRenderLayer getTileLayer(){
		return (TileRenderLayer)mLayer;
	}


	@Override
	public void onUpdate(MapPosition mapPosition, boolean changed) {

		if (mClearMap){
			mTileManager.init(mMapView.getWidth(), mMapView.getHeight());
			mClearMap = false;
			changed = true;
			Log.d(TAG, "init TileManager ----- ");
		}
		if (changed)
			mTileManager.update(mapPosition);
	}


	@Override
	public void destroy(){
		mTileManager.destroy();

		for (MapWorker mapWorker : mMapWorkers) {
			mapWorker.pause();
			mapWorker.interrupt();

			mapWorker.getTileGenerator().getMapDatabase().close();

			try {
				mapWorker.join(10000);
			} catch (InterruptedException e) {
				// restore the interrupted status
				Thread.currentThread().interrupt();
			}
		}
	}

	private void clearMap() {
		// clear tile and overlay data before next draw
		mClearMap = true;
	}

	private MapOptions mMapOptions;
	private IMapDatabase mMapDatabase;
	private String mRenderTheme;

	/**
	 * Sets the MapDatabase for this MapView.
	 *
	 * @param options
	 *            the new MapDatabase options.
	 * @return true if MapDatabase changed
	 */
	public boolean setMapDatabase(MapOptions options) {
		Log.i(TAG, "setMapDatabase: " + options.db.name());

		if (mMapOptions != null && mMapOptions.equals(options))
			return true;

		mapWorkersPause(true);

		mJobQueue.clear();
		mMapOptions = options;

		mMapDatabase = null;

		for (int i = 0; i < mNumMapWorkers; i++) {
			MapWorker mapWorker = mMapWorkers[i];

			IMapDatabase mapDatabase = MapDatabaseFactory
					.createMapDatabase(options.db);

			OpenResult result = mapDatabase.open(options);

			if (result != OpenResult.SUCCESS) {
				Log.d(TAG, "failed open db: " + result.getErrorMessage());
			}

			TileGenerator tileGenerator = mapWorker.getTileGenerator();
			tileGenerator.setMapDatabase(mapDatabase);

			// TODO this could be done in a cleaner way..
			if (mMapDatabase == null)
				mMapDatabase = mapDatabase;
		}

		if (options.db == MapDatabases.OSCIMAP_READER ||
				options.db == MapDatabases.MAP_READER ||
				options.db == MapDatabases.TEST_READER)
			MapView.enableClosePolygons = true;
		else
			MapView.enableClosePolygons = false;

		clearMap();

		mapWorkersProceed();

		return true;
	}

	public Map<String, String> getMapOptions() {
		return mMapOptions;
	}

	public MapPosition getMapFileCenter() {
		if (mMapDatabase == null)
			return null;

		MapInfo mapInfo = mMapDatabase.getMapInfo();
		if (mapInfo == null)
			return null;

		GeoPoint startPos = mapInfo.startPosition;

		if (startPos == null)
			startPos = mapInfo.mapCenter;

		if (startPos == null)
			startPos = new GeoPoint(0, 0);

		MapPosition mapPosition = new MapPosition();
		mapPosition.setPosition(startPos);

		if (mapInfo.startZoomLevel == null)
			mapPosition.setZoomLevel(12);
		else
			mapPosition.setZoomLevel((mapInfo.startZoomLevel).byteValue());

		return mapPosition;
	}
	public String getRenderTheme() {
		return mRenderTheme;
	}
	/**
	 * Sets the internal theme which is used for rendering the map.
	 *
	 * @param internalRenderTheme
	 *            the internal rendering theme.
	 * @return ...
	 * @throws IllegalArgumentException
	 *             if the supplied internalRenderTheme is null.
	 */
	public boolean setRenderTheme(InternalRenderTheme internalRenderTheme) {
		if (internalRenderTheme == null) {
			throw new IllegalArgumentException("render theme must not be null");
		}

		if (internalRenderTheme.name() == mRenderTheme)
			return true;

		boolean ret = setRenderTheme((Theme) internalRenderTheme);
		if (ret) {
			mRenderTheme = internalRenderTheme.name();
		}

		clearMap();

		return ret;
	}
	/**
	 * Sets the theme file which is used for rendering the map.
	 *
	 * @param renderThemePath
	 *            the path to the XML file which defines the rendering theme.
	 * @throws IllegalArgumentException
	 *             if the supplied internalRenderTheme is null.
	 * @throws FileNotFoundException
	 *             if the supplied file does not exist, is a directory or cannot
	 *             be read.
	 */
	public void setRenderTheme(String renderThemePath) throws FileNotFoundException {
		if (renderThemePath == null) {
			throw new IllegalArgumentException("render theme path must not be null");
		}

		boolean ret = setRenderTheme(new ExternalRenderTheme(renderThemePath));
		if (ret) {
			mRenderTheme = renderThemePath;
		}

		clearMap();
	}

	private boolean setRenderTheme(Theme theme) {

		mapWorkersPause(true);

		InputStream inputStream = null;
		try {
			inputStream = theme.getRenderThemeAsStream();
			RenderTheme t = RenderThemeHandler.getRenderTheme(inputStream);
			t.scaleTextSize(1 + (MapView.dpi / 240 - 1) * 0.5f);

			// FIXME !!!
			GLRenderer.setRenderTheme(t);

			for (TileGenerator g : mTileGenerators)
				g.setRenderTheme(t);

			return true;
		} catch (ParserConfigurationException e) {
			Log.e(TAG, e.getMessage());
		} catch (SAXException e) {
			Log.e(TAG, e.getMessage());
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
		} finally {
			try {
				if (inputStream != null) {
					inputStream.close();
				}
			} catch (IOException e) {
				Log.e(TAG, e.getMessage());
			}
			mapWorkersProceed();
		}
		return false;
	}
	/**
	 * add jobs and remember MapWorkers that stuff needs to be done
	 *
	 * @param jobs
	 *            tile jobs
	 */
	public void addJobs(JobTile[] jobs) {
		if (jobs == null) {
			mJobQueue.clear();
			return;
		}
		mJobQueue.setJobs(jobs);

		for (int i = 0; i < mNumMapWorkers; i++) {
			MapWorker m = mMapWorkers[i];
			synchronized (m) {
				m.notify();
			}
		}
	}

	private void mapWorkersPause(boolean wait) {
		for (MapWorker mapWorker : mMapWorkers) {
			if (!mapWorker.isPausing())
				mapWorker.pause();
		}
		if (wait) {
			for (MapWorker mapWorker : mMapWorkers) {
				if (!mapWorker.isPausing())
					mapWorker.awaitPausing();
			}
		}
	}

	private void mapWorkersProceed() {
		for (MapWorker mapWorker : mMapWorkers)
			mapWorker.proceed();
	}
}
