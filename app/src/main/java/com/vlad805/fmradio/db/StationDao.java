package com.vlad805.fmradio.db;

import androidx.room.*;

import java.util.List;

/**
 * vlad805 (c) 2019
 */
@Dao
public interface StationDao {

	@Query("SELECT * FROM `station`")
	List<Station> getAll();

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void add(List<Station> stations);

	@Update(onConflict = OnConflictStrategy.REPLACE)
	void update(Station station);

	@Delete
	void delete(Station station);

}
