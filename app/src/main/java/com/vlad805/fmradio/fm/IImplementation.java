package com.vlad805.fmradio.fm;

import android.annotation.SuppressLint;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.FM;

import java.util.List;

/**
 * vlad805 (c) 2019
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
public interface IImplementation {

	/**
	 * Хардкодный путь к файлам приложения
	 */
	@SuppressLint("SdCardPath")
	public static String APP_FILES_PATH = "/data/data/" + BuildConfig.APPLICATION_ID + "/files/";

	/**
	 * Выполняемые операции перед стартом тюнера
	 * @param srv Контроллер
	 * @return 0 в случае успеха инициализации
	 */
	public int init(FM srv, OnResponseReceived<Void> listener);

	/**
	 * Запуск внешнего бинарника
	 * TODO: переименовать в launch()
	 * @return 0 в случае успеха
	 */
	public int setup(OnResponseReceived<Void> listener);

	/**
	 * Старт тюнера
	 * @param srv Контроллер
	 * @return 0 в случае успеха
	 */
	public int enable(FM srv, OnResponseReceived<Void> listener);

	/**
	 * Остановка тюнера
	 * @param srv Контроллер
	 * @return 0 в случае успеха
	 */
	public int disable(FM srv, OnResponseReceived<Void> listener);

	/**
	 * Изменение текущей частоты
	 * @param srv Контроллер
	 * @param kHz Частота в килогерцах: 88.0 => 88000
	 * @return 0 в случае успеха
	 */
	public int setFrequency(FM srv, int kHz, OnResponseReceived<Void> listener);

	/**
	 * Переход на предыдущую/следующую частоту
	 * @param srv Контроллер
	 * @param direction Направление перехода: 1 - наверх, -1 - вниз
	 */
	public void jump(FM srv, int direction, OnResponseReceived<Void> listener);

	/**
	 * Получение информации о качестве сигнала
	 * @param srv Контроллер
	 * @param listener Обработчик события получения ответа
	 */
	public void getRssi(FM srv, OnResponseReceived<Integer> listener);

	/**
	 * Принудительная остановка нативного процесса
	 * @param srv Контроллер
	 */
	public void kill(FM srv, OnResponseReceived<Void> listener);

	/**
	 * Изменение мьюта. Полного или поканального
	 * @param srv Контроллер
	 * @param state Тип мьюта
	 */
	public void setMute(FM srv, MuteState state, OnResponseReceived<Void> listener);

	/**
	 * Поиск радиостанций
	 * @param srv Контроллер
	 * @param listener Обработчик события завершения поиска
	 */
	public void search(FM srv, OnResponseReceived<List<Integer>> listener);

	/**
	 * Хардвеейрная перемотка
	 * @param srv Контроллер
	 * @param direction Направление: 1 - вверх, 0 - вниз
	 * @param listener Обработчик события получения ответа
	 */
	public void hardwareSeek(FM srv, int direction, OnResponseReceived<Integer> listener);
}
