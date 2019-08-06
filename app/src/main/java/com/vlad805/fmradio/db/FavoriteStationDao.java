package com.vlad805.fmradio.db;

import androidx.room.*;

import java.util.List;

/**
 * vlad805 (c) 2019
 */
@Dao
public interface FavoriteStationDao {

	@Query("SELECT * FROM `favorite_station`")
	List<FavoriteStation> getAll();

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void add(List<FavoriteStation> stations);

	@Update(onConflict = OnConflictStrategy.REPLACE)
	void update(List<FavoriteStation> stations);

	@Delete
	void delete(FavoriteStation station);

}
