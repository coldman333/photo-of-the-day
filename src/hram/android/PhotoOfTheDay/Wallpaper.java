package hram.android.PhotoOfTheDay;

import hram.android.PhotoOfTheDay.Exceptions.ConnectionException;
import hram.android.PhotoOfTheDay.Exceptions.IncorrectDataFormat;
import hram.android.PhotoOfTheDay.Parsers.BaseParser;
import hram.android.PhotoOfTheDay.Parsers.EarthShots;
import hram.android.PhotoOfTheDay.Parsers.Flickr;
import hram.android.PhotoOfTheDay.Parsers.Nasa;
import hram.android.PhotoOfTheDay.Parsers.NationalGeographic;
import hram.android.PhotoOfTheDay.Parsers.TestParser;
import hram.android.PhotoOfTheDay.Parsers.Wikipedia;
import hram.android.PhotoOfTheDay.Parsers.Yandex;
import hram.android.PhotoOfTheDay.appwidget.WidgetBroadcastEnum;
import hram.android.PhotoOfTheDay.appwidget.WidgetBroadcastReceiver;
import hram.android.PhotoOfTheDay.help.HelpActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.bugsense.trace.BugSenseHandler;
import com.novoda.imageloader.core.util.DirectLoader;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.util.DisplayMetrics;
//import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.widget.Toast;

public class Wallpaper extends WallpaperService 
{
	public static final String TAG = Constants.TAG;
	private final Handler mHandler = new Handler();
	private List<MyEngine> engines = new ArrayList<MyEngine>();
	private Lock l = new ReentrantLock();
	private NetworkInfo mWifi;

	private int currDay = -1;
	private Bitmap bm;
	public SharedPreferences preferences;
	private String currentUrl;
	//private BaseParser parser;
	private int currentParser = -1;
	private int currentHeight = -1;
	private int currentWidth = -1;

	@Override
	public void onCreate() 
	{
		//Log.i(TAG, "Создание сервиса.");
		BugSenseHandler.initAndStartSession(this, Constants.BUG_SENSE_APIKEY);
		
		// настройки
		preferences = getSharedPreferences(Constants.SETTINGS_NAME, 0);

		ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		
		try {
			SetCurrentParser(Integer.decode(preferences.getString(Constants.SOURCES_NAME, "1")));
		} catch (Exception e) {
			preferences.edit().putString(Constants.SOURCES_NAME, "1").commit();
			SetCurrentParser(1);
		}
		
		ReadFile();
		
		try {
			showHelpOnFirstLaunch();
		} catch (Exception e) {
			//BugSenseHandler.sendExceptionMessage("Wallpaper", "showHelpOnFirstLaunch", e);
		}
	}

	@Override
	public void onDestroy() {
		// Log.i(TAG, "Удаление сервиса.");
		// unregisterReceiver(widgetReceiver);
	}

	@Override
	public Engine onCreateEngine() {
		return new MyEngine(this);
	}

	/**
	 * We want the help screen to be shown automatically the first time a new version of the app is
	 * run. The easiest way to do this is to check android:versionCode from the manifest, and compare
	 * it to a value stored as a preference.
	 */
	private boolean showHelpOnFirstLaunch() 
	{
		try 
		{
			PackageInfo info = getPackageManager().getPackageInfo(this.getPackageName(), 0);
			int currentVersion = info.versionCode;
			// Since we're paying to talk to the PackageManager anyway, it makes sense to cache the app
			// version name here for display in the about box later.
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			int lastVersion = prefs.getInt(Constants.KEY_HELP_VERSION_SHOWN, 0);
			if (currentVersion > lastVersion)
			//if (true) 
			{
				prefs.edit().putInt(Constants.KEY_HELP_VERSION_SHOWN, currentVersion).commit();
				Intent intent = new Intent(this, HelpActivity.class);
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				// Show the default page on a clean install, and the what's new page on an upgrade.
				String page = lastVersion == 0 ? HelpActivity.DEFAULT_PAGE : HelpActivity.WHATS_NEW_PAGE;
				//page = HelpActivity.DEFAULT_PAGE;
				intent.putExtra(HelpActivity.REQUESTED_PAGE_KEY, page);
				startActivity(intent);
				return true;
			}
	    } catch (PackageManager.NameNotFoundException e) {
	    }
	    return false;
	}
	
	/**
	 * Регистрирует рисовальщик, добавляет в список, после обновления фото
	 * рисовальщики оповещаются из этого списка
	 * 
	 * @param object
	 */
	public void RegEngine(MyEngine object) {
		engines.add(object);
	}

	/**
	 * Отменяет регистрацию рисовальщика
	 * 
	 * @param object
	 */
	public void UnregEngine(MyEngine object) {
		engines.remove(object);
	}

	/**
	 * Сохраняет указатель на картинку
	 * 
	 * @param value
	 */
	public void SetBitmap(Bitmap value) {
		// Log.d(TAG, "Сохранение указателя картинки");
		bm = value;
	}

	/**
	 * Возвращает указатель на картинку
	 * 
	 * @return
	 */
	public Bitmap GetBitmap() {
		return bm;
	}

	/**
	 * Сохраняет текущий день
	 * 
	 * @param value
	 */
	public void SetCurrentDay(int value) {
		// для отладки обновления
		// value -= 1;

		// Log.d(TAG, String.format("Текущее число: %d", value));
		currDay = value;
	}

	/**
	 * Возвращает текущий день
	 * 
	 * @return
	 */
	public int GetCurrentDay() {
		return currDay;
	}

	/**
	 * Сохраняет URL текущей картинки
	 * 
	 * @param value
	 */
	public void SetCurrentUrl(String value) {
		// Log.d(TAG, String.format("Текущий URL: %s", value));
		currentUrl = value;
	}

	/**
	 * Возвращает URL текущей картинки
	 * 
	 * @return
	 */
	public String GetCurrentUrl() {
		return currentUrl;
	}

	/**
	 * Возвращает статус услуги передачи данных
	 * 
	 * @return
	 */
	public boolean IsOnline() {
		// Log.d(TAG, "Вызов isOnline()");

		try {
			ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			return cm.getActiveNetworkInfo().isConnectedOrConnecting();
		} catch (Exception e) {
			// Log.d(TAG, "Ошибка проверки online");
		}

		return false;
	}

	public boolean IsWiFiEnabled() {
		boolean wifiOnly = preferences.getBoolean(Constants.WIFI_ONLY, false);

		// Log.d(TAG, String.format("Только через WiFi %s", wifiOnly ? "Вкл" :
		// "Откл"));

		return wifiOnly ? mWifi.isConnected() : true;
	}

	/**
	 * Возвращает урл картинки дня
	 * 
	 * @return
	 * @throws IOException
	 */
	public String GetUrl() throws IOException, IncorrectDataFormat {
		// Log.d(TAG, "Получение URL картинки");

		return createCurrentParser().GetUrl();
	}

	public String getImageNamePrefix() {
		return createCurrentParser().getImageNamePrefix();
	}

	/**
	 * Создает экземпляр выбранного парсера
	 * 
	 * @param value номер парсера
	 * @return
	 */
	public boolean SetCurrentParser(int value) {
		l.lock();
		try {
			if (currentParser == value) {
				return false;
			}
			currentParser = value;

		} finally {
			l.unlock();
		}

		ResetBitmap();
		return true;
	}

	public int getCurrentParser() {
		return currentParser;
	}
	
	public BaseParser createCurrentParser() 
	{
		switch (getCurrentParser()) 
		{
			case 1:
				return new Yandex(this, preferences);
			case 2:
				return new Flickr(this, preferences);
			case 3:
				return new NationalGeographic();
			case 4:
				return new Nasa();
			case 5:
				return new Wikipedia();
			case 6:
				return new TestParser(this, preferences);
			case 7:
				return new EarthShots();
			default:
				// Log.i(TAG, "Создание парсера по умолчанию");
				return new Yandex(this, preferences);
		}
	}

	/**
	 * Сброс настроек для того чтоб отрисовывалась картинка загрузки
	 */
	public void ResetBitmap() {
		SetBitmap(null);
		currDay = -1;
		currentUrl = null;
	}

	/**
	 * Чтение сохраненной картинки из файла
	 */
	public void ReadFile() {
		// Log.d(TAG, "Чтение картинки из файла");

		FileInputStream stream = null;
		try {
			long lastUpdate = preferences.getLong(Constants.LAST_UPDATE, 0);
			if (lastUpdate == 0) {
				return;
			}

			SetCurrentUrl(preferences.getString(Constants.LAST_URL, ""));
			
			Calendar c = Calendar.getInstance();
			c.setTimeInMillis(lastUpdate);
			SetCurrentDay(c.get(Calendar.DATE));

			if (GetCurrentUrl().length() > 0) {
				stream = openFileInput(Constants.FILE_NAME);
				bm = BitmapFactory.decodeStream(stream);
				// Log.d(TAG, "Считана картинка из файла");

				currentHeight = bm.getHeight();
				currentWidth = bm.getWidth();
				// Log.d(TAG, String.format("Ширина: %d, Высота: %d",
				// currentWidth, currentHeight));
			}

		} catch (Exception e) {
			// Log.d(TAG, "Не известная ошибка");
			ResetBitmap();
		} finally {
			if (stream != null)
				try {
					stream.close();
				} catch (IOException e) {
				}
		}

	}

	/**
	 * Сохранение картинки в файл
	 * 
	 * @param bm
	 * @param url
	 */
	public void SaveFile(Bitmap bm, String url) {
		// Log.d(TAG, "Сохранение картинки в файл");
		try {
			FileOutputStream fos = openFileOutput(Constants.FILE_NAME, Context.MODE_PRIVATE);
			// PNG which is lossless, will ignore the quality setting
			bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
			fos.close();

			long now = System.currentTimeMillis();

			// сохранение времени последнего обновления
			SharedPreferences.Editor editor = preferences.edit();
			editor.putLong(Constants.LAST_UPDATE, now);
			editor.putString(Constants.LAST_URL, url);
			editor.commit();

			SetCurrentDay(Calendar.getInstance().get(Calendar.DATE));
			SetCurrentUrl(url);

		} catch (IOException e) {
			// Log.d(TAG, "Ошибка сохранения картинки");
		}
	}

	/**
	 * Создание и запуск потока обновления
	 */
	public void StartUpdate() {
		new Thread(new Runnable() {
			public void run() {
				update();
			}
		}).start();
	}

	/**
	 * Проверка условий ибновления и в случае выполнения загрузка фото,
	 * установка его текущим и сохранение в файл
	 */
	public void update() {
		// Log.d(TAG, "Вызов MyEngine.update()");

		try {
			if (IsOnline() == false) {
				throw new ConnectionException("Нет интернет соединения.");
			}

			String url = GetUrl();
			if (url == null) {
				throw new ConnectionException("Ошибка получения URL картинки");
			}

			if (url.equals(GetCurrentUrl())) {
				// Log.d(TAG, "URL совпадает, еще не обновили");
				return;
			}

			// Log.d(TAG, "Загрузка картинки по адресу: " + url);
			//Bitmap bm = ImageDownloader.loadImageFromUrl(url);
			Bitmap bm = new DirectLoader().download(url);
			if (bm == null) {
				throw new ConnectionException("Ошибка загрузки киртинки");
			}

			// Log.d(TAG, "Картинка успешно загружена");
			currentHeight = -1;
			currentWidth = -1;
			SetBitmap(bm);
			SaveFile(bm, url);

			for (MyEngine info : engines) {
				// Log.d(TAG, "Вызов drawFrame()");
				info.drawFrame();
			}
		} catch (IOException e) {
			// Log.w(TAG,
			// String.format("Ошибка получения URL: %s. Запуск проверяльщика",
			// e.getLocalizedMessage()));
			CheckOnline();
		} catch (ConnectionException e) {
			// Log.w(TAG, String.format("%s. Запуск проверяльщика",
			// e.getMessage()));
			CheckOnline();
		}  catch (IncorrectDataFormat e) {
			try{
				BugSenseHandler.sendExceptionMessage("IncorrectDataFormat", "" + getCurrentParser(), e);
			}catch (Exception e2) {}
		} catch (Exception e) {
			try{
				BugSenseHandler.sendExceptionMessage("Wallpaper.update", "" + getCurrentParser(), e);
			}catch (Exception e2) {}
		}
	}
	
	/**
	 * Создание и запуск таймера проверки наличия услуги передачи данных
	 */
	private void CheckOnline() {
		// Log.d(TAG, "Создание таймера");

		try {
			new Timer().scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					// Log.d(TAG, "Запуск проверки соединения");
					if (IsOnline() == false) {
						// Log.d(TAG, "Передача данных отключена");
						return;
					}

					// Log.d(TAG,
					// "Передача данных включена. Остановка таймера проверки соединения");
					if (cancel()) {
						// Log.d(TAG, "Таймер успешно остановлен");
					}

					// Log.d(TAG, "Запуск обновления");
					StartUpdate();
				}

			}, 10000, 10000);
		} catch (Exception e) {
			// Log.e(TAG, "Неизвестная ошибка: " + e.getLocalizedMessage());
		}

		// Log.d(TAG, "Таймер создан и запущен");
	}

	public class MyEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
		private final Paint mPaint = new Paint();
		private int mPixels;
		private float mXStep;
		private Timer timer = new Timer();
		private int mHeight = -1;
		private int mWidth = -1;
		private Wallpaper wp;
		// private Rect mRectFrame;
		private boolean mHorizontal;
		private Bitmap download;
		private SharedPreferences preferences;
		final WidgetBroadcastReceiver widgetReceiver;

		private final Runnable drawRunner = new Runnable() {
			public void run() {
				drawFrame();
			}
		};

		private boolean mVisible;

		/**
		 * Конструктор рисовальщика
		 * 
		 * @param service
		 *            ссылка на сервис обоев
		 */
		MyEngine(Wallpaper service) {
			// Log.i(TAG, "Создание Engine");

			final Paint paint = mPaint;
			paint.setColor(0xffffffff);
			paint.setTextSize(30);
			paint.setAntiAlias(true);
			paint.setTextAlign(Align.CENTER);

			preferences = Wallpaper.this.getSharedPreferences(Constants.SETTINGS_NAME, 0);
			preferences.registerOnSharedPreferenceChangeListener(this);

			wp = service;
			download = BitmapFactory.decodeResource(getResources(), R.drawable.download);
			widgetReceiver = new WidgetBroadcastReceiver(wp, this);
		}

		@Override
		public void onCreate(SurfaceHolder surfaceHolder) {
			super.onCreate(surfaceHolder);

			wp.RegEngine(this);
			// Log.d(TAG, "Вызов MyEngine.onCreate()");

			netUpdates();
			registerReceiver(widgetReceiver, new IntentFilter(WidgetBroadcastEnum.SAVE_ACTION));
			registerReceiver(widgetReceiver, new IntentFilter(WidgetBroadcastEnum.OPEN_GALLERY_ACTION));
			registerReceiver(widgetReceiver, new IntentFilter(WidgetBroadcastEnum.NEXT_PARSER_ACTION));
			registerReceiver(widgetReceiver, new IntentFilter(WidgetBroadcastEnum.SETTINGS_ACTION));
			registerReceiver(widgetReceiver, new IntentFilter(WidgetBroadcastEnum.CHANGE_SETTINGS_ACTION));
		}

		@Override
		public void onDestroy() {
			// Log.i(TAG, "Удаление Engine");

			wp.UnregEngine(this);

			timer.cancel();
			mHandler.removeCallbacks(drawRunner);
			if (preferences != null) {
				preferences.unregisterOnSharedPreferenceChangeListener(this);
			}
			
			// https://www.bugsense.com/dashboard/project/ab3f3ed5#error/67667495
			try
			{ 
				unregisterReceiver(widgetReceiver);
			}
			catch(java.lang.IllegalArgumentException e){ 
				BugSenseHandler.sendExceptionMessage("error/67667495", "После исправления", e);
			}
			catch(Exception e){
				BugSenseHandler.sendExceptionMessage("unregisterReceiver", "После исправления", e);
			}
			super.onDestroy();
		}

		/**
		 * Таймер обновления фотографии. Проверяет смену дня. В случае если
		 * наступил след. день запускает обновление.
		 */
		private void netUpdates() {
			// Log.d(TAG, "Создание таймера обновлений");
			
			timer.scheduleAtFixedRate(new TimerTask() 
			{
				@Override
				public void run() 
				{
					try {
						// Log.d(TAG, "Сработал таймер обновления");
						if (IsNeedDownloadEveryUpdate()) {
							// Log.d(TAG, "Запуск периодического обновления");
							wp.StartUpdate();
							return;
						}
	
						// Log.d(TAG, "Проверка времени последнего обновления");
						if (wp.GetCurrentDay() != Calendar.getInstance().get(Calendar.DATE)) {
							// Log.d(TAG, "Запуск обновления");
							wp.StartUpdate();
						} else {
							// Log.d(TAG,
							// String.format("Обновление не нужно. Сейчас: %d, текущий: %d",
							// now, wp.GetCurrentDay()));
						}
					} catch (Exception e) {
						// Log.e(TAG, "Неизвестная ошибка: " + e.getLocalizedMessage());
					}
				}

			}, 0, Constants.UPDATE_INTERVAL);

			// Log.d(TAG, "Таймер обновлений запущен");
		}

		/**
		 * Возвращает флаг того, что необходимо обновлять картинку при каждой проверке.
		 * Флаг используется для того чтобы картинки обновлялись ежечастно по тегу 
		 * если источник обоев поддерживает теги или для превью.  
		 * @return true если необходимо обновлять при каждой проверке
		 */
		private boolean IsNeedDownloadEveryUpdate() 
		{
			try {
				// Log.d(TAG, "если это превью то не обновляем по таймеру");
				if (isPreview()) {
					// Log.d(TAG, "это превью не обновляем по таймеру");
					return false;
				}

				// Log.d(TAG, "если парсер не поддерживает");
				if (createCurrentParser().IsTagSupported() == false) {
					// Log.d(TAG, "парсер не поддерживает");
					return false;
				}

				// Log.d(TAG, "если не включена работа по тегам");
				if (preferences.getBoolean("tagPhotoEnable", false) == false) {
					// Log.d(TAG, "не включена работа по тегам");
					return false;
				}

				// Log.d(TAG,
				// "если опция периодического обновления не включена");
				if (preferences.getBoolean("downloadEveryUpdate", false) == false) {
					// Log.d(TAG,
					// "опция периодического обновления не включена");
					return false;
				}

				// Log.d(TAG, "если тег не введен");
				String tag = preferences.getString("tagPhotoValue", "");
				if (tag.length() == 0) {
					// Log.d(TAG, "тег не введен");
					return false;
				}
			} catch (Exception e) {
				// Log.e(TAG, e.getMessage());
				return false;
			}

			// Log.d(TAG, "надо обновлять");
			return true;
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			// Log.d(TAG, "Вызов MyEngine.onVisibilityChanged()");

			mVisible = visible;
			if (visible) {
				drawFrame();
			} else {
				mHandler.removeCallbacks(drawRunner);
			}
		}

		@Override
		public void onSurfaceChanged(SurfaceHolder holder, int format,
				int width, int height) {
			super.onSurfaceChanged(holder, format, width, height);

			// Log.d(TAG, "Вызов MyEngine.onSurfaceChanged()");

			mHeight = height;
			mWidth = width;
			initFrameParams();
			drawFrame();
		}

		@Override
		public void onSurfaceCreated(SurfaceHolder holder) {
			super.onSurfaceCreated(holder);
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder) {
			super.onSurfaceDestroyed(holder);
			mVisible = false;
			mHandler.removeCallbacks(drawRunner);
		}

		@Override
		public void onOffsetsChanged(float xOffset, float yOffset, float xStep,
				float yStep, int xPixels, int yPixels) {
			// Log.d(TAG, "Вызов MyEngine.onOffsetsChanged()");
			// Log.d(TAG, String.format("xStep: %f, xPixels: %d", xStep,
			// xPixels));

			mXStep = xStep;
			mPixels = xPixels;
			drawFrame();
		}

		// для тестирования вывода ошибки о том что мало памяти
		/*
		void drawFrameOutOf() {
			// Log.d(TAG, "Процедура отрисовки");

			final SurfaceHolder holder = getSurfaceHolder();

			Canvas c = null;
			try {
				
				c = holder.lockCanvas();
				if (c != null) {
					c.drawText(getText(R.string.error).toString(), mWidth / 2, mHeight / 2 - 50, mPaint);
					c.drawText(getText(R.string.isOutOfMemory1).toString(), mWidth / 2, mHeight / 2, mPaint);
					c.drawText(getText(R.string.isOutOfMemory2).toString(), mWidth / 2, mHeight / 2 + 50, mPaint);
				}
			} finally {
				if (c != null)
					holder.unlockCanvasAndPost(c);

				// Reschedule the next redraw
				mHandler.removeCallbacks(drawRunner);
				if (mVisible) {
					// mHandler.postDelayed(drawRunner, 1000);
				}
			}
		}
		*/	
				
		/**
		 * Draw one frame of the animation. This method gets called repeatedly
		 * by posting a delayed Runnable. You can do any drawing you want in
		 * here. This example draws a wireframe cube.
		 */
		void drawFrame() {
			// Log.d(TAG, "Процедура отрисовки");

			final SurfaceHolder holder = getSurfaceHolder();

			Canvas c = null;
			try {
				c = holder.lockCanvas();
				Bitmap bm = wp.GetBitmap();
				if (c != null) {
					if (bm == null) {
						// Log.d(TAG, "Картинки нет рисуем загрузку");
						double rescaling = (double) mWidth / download.getWidth();
						int width = (int) (download.getWidth() * rescaling);
						int offset = (mHeight / 2) - (width / 2);
						c.drawRect(new Rect(0, 0, mWidth, mHeight), new Paint());
						c.drawBitmap(Bitmap.createScaledBitmap(download, (int) (download.getWidth() * rescaling), (int) (download.getHeight() * rescaling), true), 0, offset, null);
						if (IsOnline()) {
							c.drawText(getText(R.string.download).toString(), mWidth / 2, 100, mPaint);
						} else {
							c.drawText(getText(R.string.error).toString(), mWidth / 2, 100, mPaint);
							c.drawText(getText(R.string.isOffline).toString(), mWidth / 2, 150, mPaint);
						}
						return;
					}

					if (mHeight != currentHeight || mWidth != currentWidth) {
						// Log.d(TAG,String.format("Изменились размеры, изменяем размер: %d->%d, %d->%d", currentHeight, mHeight, currentWidth, mWidth));
						double rescaling = (double) mHeight / bm.getHeight();
						if (mHorizontal) {
							rescaling = (double) mWidth / bm.getWidth();
							rescaling *= 1.5;
						}

						try
						{
							bm = Bitmap.createScaledBitmap(bm, (int) (bm.getWidth() * rescaling), (int) (bm.getHeight() * rescaling), true);
							wp.SetBitmap(bm);
							currentHeight = mHeight;
							currentWidth = mWidth;
						}catch (OutOfMemoryError e) {
							System.gc();
							c.drawText(getText(R.string.error).toString(), mWidth / 2, 100, mPaint);
							c.drawText(getText(R.string.isOutOfMemory1).toString(), mWidth / 2, 150, mPaint);
							c.drawText(getText(R.string.isOutOfMemory2).toString(), mWidth / 2, 200, mPaint);
							/*
							try{
								// логирование показало что картинки скачиваются нормальные (корректный URL) и нормально отображаются (проверено с пом. тестового парсера)
								String msg = String.format("URL: %s, Width: %d, Height: %d, mWidth: %d, mHeight: %d, rescaling: %f", GetCurrentUrl(), bm.getWidth(), bm.getHeight(), mWidth, mHeight, (float)rescaling);
								BugSenseHandler.sendExceptionMessage("createScaledBitmap", msg, new hram.android.PhotoOfTheDay.Exceptions.OutOfMemoryError(e.getMessage()));
							}catch (Exception e2){
							}finally{
								ResetBitmap();
							}
							*/
							return;
						}
					}

					if (isPreview() == false) {
						float step1 = mWidth * mXStep;
						float step2 = (bm.getWidth() - mWidth) * mXStep;
						float dX = (float) mPixels * (step2 / step1);
						c.translate(dX, 0f);
					}

					if (mHorizontal)
						c.drawBitmap(bm, 0, -currentHeight / 3, null);
					else
						c.drawBitmap(bm, 0, 0, null);
				}
			} finally {
				if (c != null)
					holder.unlockCanvasAndPost(c);

				// Reschedule the next redraw
				mHandler.removeCallbacks(drawRunner);
				if (mVisible) {
					// mHandler.postDelayed(drawRunner, 1000);
				}
			}
		}

		void initFrameParams() {
			DisplayMetrics metrics = new DisplayMetrics();
			Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
			display.getMetrics(metrics);

			// mRectFrame = new Rect(0, 0, metrics.widthPixels,
			// metrics.heightPixels);

			int rotation = display.getOrientation();
			if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
				mHorizontal = false;
			} else {
				mHorizontal = true;
			}
		}

		public void onPreferenceChanged(String key) {
			// Log.d(TAG, "Изменено " + key);
			String tag = preferences.getString("tagPhotoValue", "");
			if (key.equals("tagPhotoEnable") && createCurrentParser().IsTagSupported()) {
				if (preferences.getBoolean(key, false) && tag.length() == 0) {
					return;
				}

				StartUpdate();
			}
			if (key.equals("tagPhotoValue") && createCurrentParser().IsTagSupported() && tag.length() > 0) {
				StartUpdate();
			} else if (key.equals("sources")) {
				String value = preferences.getString(key, "0");

				if (SetCurrentParser(Integer.decode(value))) {
					StartUpdate();
				}
			}
		}
		
		public void onSharedPreferenceChanged(SharedPreferences prefs, String arg1) 
		{
			if (isPreview() == false) {
				return;
			}

			// Log.d(TAG, "Изменено " + arg1);
			String tag = prefs.getString("tagPhotoValue", "");
			if (arg1.equals("tagPhotoEnable") && createCurrentParser().IsTagSupported()) {
				if (prefs.getBoolean(arg1, false) && tag.length() == 0) {
					return;
				}

				StartUpdate();
			}
			if (arg1.equals("tagPhotoValue") && createCurrentParser().IsTagSupported() && tag.length() > 0) {
				StartUpdate();
			} else if (arg1.equals("sources")) {
				String value = prefs.getString(arg1, "0");

				if (SetCurrentParser(Integer.decode(value))) {
					StartUpdate();
				}
			}
		}

		private void StartUpdate() 
		{
			if (wp.IsWiFiEnabled() == false) {
				return;
			}

			Toast.makeText(wp, getString(R.string.updateStarted), Toast.LENGTH_SHORT).show();

			wp.ResetBitmap();

			// сброс времени последнего обновления
			SharedPreferences.Editor editor = preferences.edit();
			editor.putLong(Constants.LAST_UPDATE, 0);
			editor.putString(Constants.LAST_URL, "");
			editor.commit();

			wp.StartUpdate();
		}
	}
}
