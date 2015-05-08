/**********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - Diego Ruiz - BX Service GmbH                                      *
 **********************************************************************/

package bxservice.idempiere.window.rp.validator;

import org.adempiere.util.Callback;
import org.adempiere.webui.adwindow.ADWindow;
import org.adempiere.webui.adwindow.ADWindowContent;
import org.adempiere.webui.adwindow.validator.WindowValidator;
import org.adempiere.webui.adwindow.validator.WindowValidatorEvent;
import org.adempiere.webui.adwindow.validator.WindowValidatorEventType;
import org.compiere.model.GridTab;

public class SoTrxWindowValidator implements WindowValidator{

	@Override
	public void onWindowEvent(WindowValidatorEvent event,
			Callback<Boolean> callback) {
		if ( event.getName().equals(WindowValidatorEventType.AFTER_DELETE.getName()) ) {
			ADWindow window = event.getWindow();
			if(window != null){
				ADWindowContent windowContent = window.getADWindowContent();
				if( windowContent!= null ){
					GridTab activeTab = windowContent.getActiveGridTab();
					if( activeTab!=null ){
						activeTab.dataRefreshAll();
					}
				}
			}
		} else {
			callback.onCallback(Boolean.TRUE);
		}
	}

}
