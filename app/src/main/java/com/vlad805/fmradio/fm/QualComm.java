package com.vlad805.fmradio.fm;

import android.util.Log;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.FM;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * vlad805 (c) 2019
 * Реализация для QualComm процессоров до 625 включительно.
 */
public class QualComm implements IImplementation {

	/**
	 * Включение детальных логов
	 */
	private static final boolean DEBUG = false;

	private boolean mEnabled = false;

	private void checkEnabled() {
		if (!mEnabled) {
			mEnabled = true;
		}
	}

	private ICallback createCallback(OnResponseReceived<Void> listener) {
		return listener != null ? data -> listener.onResponse(null) : null;
	}

	/**
	 * Инициализация
	 * Копируется архитектурно-зависимый бинарник в директорию
	 * приложения /data/data/{APP_ID}/files/, отсюда имеется
	 * возможность задать права на запуск (rwxrwxrw) и его запустить
	 *
	 * @param srv Контроллер
	 * @return 0 в случае успеха
	 */
	@Override
	public int init(final FM srv, final OnResponseReceived<Void> listener) {
		Log.i("QC", "init");

		String bin = getBinaryName();
		String resBin = APP_FILES_PATH + bin;

		File dir = new File(APP_FILES_PATH);
		if (!dir.exists()) {
			dir.mkdirs();
		}

		//return 0;

		// FileNotFoundException - Text file busy
		boolean result = srv.copyBinary(bin, resBin);
		int ec = Utils.shell("chmod 777 " + resBin + " 1>/dev/null 2>/dev/null", true);

		if (listener != null) {
			listener.onResponse(null);
		}

		return result && ec == 0 ? 0 : 1;
	}

	/**
	 * Старт архитектурно-зависимого бинарника
	 *
	 * @return 0 в случае успеха
	 */
	@Override
	public int setup(OnResponseReceived<Void> listener) {
		Log.i("QC", "setup");

		if (!DEBUG) {
			String cmd = String.format("killall %1$s 1>/dev/null 2>/dev/null; %2$s%1$s 1>/dev/null 2>/dev/null &", getBinaryName(), APP_FILES_PATH);

			Utils.shell(cmd, true);

			if (listener != null) {
				listener.onResponse(null);
			}

			return 0;
		} else {
			if (listener != null) {
				listener.onResponse(null);
			}
			Log.e("PATH", APP_FILES_PATH.concat(getBinaryName()));
		}

		return 0;
	}

	/**
	 * Возвращает имя файла для бинарника в зависимости от архитектуры
	 * @return filename Имя бинарника
	 */
	private String getBinaryName() {
		return "fmbin-" + Utils.determineArch();
	}

	/**
	 * Включение радио
	 * @param srv Контроллер
	 * @return 0 в случае успеха
	 */
	@Override
	public int enable(FM srv, OnResponseReceived<Void> listener) {
		Log.i("QC", "enable");
		srv.sendCommand(new DSRequest("enable", 5000), createCallback(listener));

		return 0;
	}

	/**
	 * Отключение радио
	 * @param srv Контроллер
	 * @return 0 в случае успеха
	 */
	@Override
	public int disable(FM srv, OnResponseReceived<Void> listener) {
		srv.sendCommand(new DSRequest("disable", 5000), createCallback(listener));
		return 0;
	}

	/**
	 * Переключение на заданную частоту
	 * @param srv Контроллер
	 * @param kHz Частота в килогерцах: 88.0 => 88000
	 * @return 0 в случае успеха
	 */
	@Override
	public int setFrequency(FM srv, int kHz, OnResponseReceived<Void> listener) {
		srv.sendCommand("setfreq " + kHz, createCallback(listener));
		return 0;
	}

	/**
	 *
	 * @param srv Контроллер
	 * @param direction Направление перехода: 1 - наверх, -1 - вниз
	 */
	@Override
	public void jump(FM srv, int direction, OnResponseReceived<Void> listener) {
		srv.sendCommand("jump " + direction, createCallback(listener));
	}

	/**
	 * Получение оценки качества сигнала
	 * @param srv Контроллер
	 * @param listener Обработчик события получения ответа
	 */
	@Override
	public void getRssi(FM srv, final OnResponseReceived<Integer> listener) {
		if (listener == null) {
			return;
		}
		srv.sendCommand("getrssi", data -> {
			int kHz;

			try {
				kHz = Integer.valueOf(data);
			} catch (NumberFormatException e) {
				kHz = -7;
			}

			listener.onResponse(kHz);
		});
	}

	/**
	 * Перемотка
	 * @param srv Контроллер
	 * @param direction Направление: 1 - вверх, 0 - вниз
	 * @param listener Обработчик события получения ответа
	 */
	@Override
	public void hardwareSeek(FM srv, int direction, final OnResponseReceived<Integer> listener) {
		String cmd = String.format(Locale.ENGLISH, "seekhw %d", direction);

		srv.sendCommand(new DSRequest(cmd, 15000), data -> {
			int kHz;
			Log.i("HW_SEEK", "data res = " + data);
			try {
				kHz = Integer.valueOf(data);
			} catch (NumberFormatException e) {
				kHz = -2;
			}

			if (listener != null) {
				listener.onResponse(kHz);
			}
		});
	}

	@Override
	public void setMute(FM srv, MuteState state, OnResponseReceived<Void> listener) {
		String cmd = String.format(Locale.ENGLISH, "setmute %d", state.getState());

		srv.sendCommand(cmd, null);
	}

	@Override
	public void search(FM srv, final OnResponseReceived<List<Integer>> listener) {
		srv.sendCommand("search", null);
	}

	/**
	 * Убивает процесс архитектурно-зависимого бинарника
	 * @param srv Контроллер
	 */
	@Override
	public void kill(FM srv, OnResponseReceived<Void> listener) {
		Log.i("QC", "kill");
		srv.sendCommand(new DSRequest("exit", 100), createCallback(listener));
	}
}
