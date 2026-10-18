/*
 * Copyright (C) 2026 Contributors to Amaze File Manager.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.database.models.utilities;

import com.amaze.filemanager.database.UtilitiesDatabase;

import androidx.room.Entity;

/**
 * {@link Entity} representation of <code>webdav</code> table in utilities.db.
 *
 * @see UtilitiesDatabase
 */
@Entity(tableName = UtilitiesDatabase.TABLE_WEBDAV)
public class WebdavEntry extends OperationDataWithName {

  public WebdavEntry(String name, String path) {
    super(name, path);
  }
}
