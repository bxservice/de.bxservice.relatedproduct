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

package org.bxservice.model;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.osgi.service.event.Event;

public class ValidatorRelatedProduct extends AbstractEventHandler{

	/**	Logger			*/
	private static CLogger log = CLogger.getCLogger(ValidatorRelatedProduct.class);


	@Override
	protected void initialize() {
		log.warning("");

		//Invoice (Customer)
		registerTableEvent(IEventTopics.PO_AFTER_NEW, MInvoiceLine.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, MInvoiceLine.Table_Name);

		//Sales Order
		registerTableEvent(IEventTopics.PO_AFTER_NEW, MOrderLine.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, MOrderLine.Table_Name);

	} //initialize

	@Override
	protected void doHandleEvent(Event event) {
		String type = event.getTopic();
		PO po = getPO(event);
		log.info(po.get_TableName() + " Type: "+type);

		// Model Events
		if (po instanceof MInvoiceLine && 
				(type.equals(IEventTopics.PO_AFTER_NEW) || 
						type.equals(IEventTopics.PO_AFTER_CHANGE) ) ) {

			createSupplementalInvoiceLines((MInvoiceLine)po, type);

		}
		if (po instanceof MOrderLine && 
				(type.equals(IEventTopics.PO_AFTER_NEW) || 
						type.equals(IEventTopics.PO_AFTER_CHANGE) ) ) {

			createSupplementalOrderLines((MOrderLine)po, type);

		}
	} //doHandleEvent

	public void createSupplementalOrderLines(MOrderLine orderLine, String type){

		MOrder order = orderLine.getParent();
		MProduct product = orderLine.getProduct();

		if ( product != null && order.isSOTrx() && hasRelatedProducts(product) ){
			try {
				int lineNo = orderLine.getLine();
				
				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)){
					for( MOrderLine line : order.getLines() ){
						if( line.get_Value("Bay_MasterOrderLine_ID")!=null && 
								line.get_Value("Bay_MasterOrderLine_ID").equals(orderLine.get_ID()) ){
							line.deleteEx(true, order.get_TrxName());
						}
					}
				}

				for (MRelatedProduct related : MRelatedProduct.getRelatedLines(product))
				{
					// The conditional UOM works to check if the parent product has that UOM the related product is created. If it's blank it's always created.
					int conditionalUOM = related.get_ValueAsInt("C_UOM_ID");

					if( conditionalUOM == 0 || conditionalUOM == orderLine.getC_UOM_ID() ){

						MOrderLine newLine = new MOrderLine(order);
						newLine.setLine(++lineNo);
						newLine.setM_Product_ID(related.getRelatedProduct_ID(), true);
						newLine.setQty(orderLine.getQtyEntered());
						if (related.getDescription() != null)
							newLine.setDescription(related.getDescription());
						newLine.setPrice();
						newLine.set_ValueOfColumn("Bay_MasterOrderLine_ID", orderLine.get_ID());
						newLine.saveEx(order.get_TrxName());
					}
				}
			} catch (Exception e) {
			     throw new AdempiereException("Error creating order line. Cause: " + e.getLocalizedMessage());
			}
		}
	} //createSupplementalOrderLines

	public void createSupplementalInvoiceLines(MInvoiceLine invoiceLine, String type){

		MInvoice invoice = invoiceLine.getParent();
		MProduct product = invoiceLine.getProduct();

		if ( product != null && invoice.isSOTrx() && hasRelatedProducts(product) ){
			try {
				
				int lineNo = invoiceLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)){
					for(MInvoiceLine line :invoice.getLines()){
						if( line.get_Value("Bay_MasterInvoiceLine_ID")!=null && 
								line.get_Value("Bay_MasterInvoiceLine_ID").equals(invoiceLine.get_ID()) ){
							line.deleteEx(true, invoice.get_TrxName());
						}
					}
				}

				for (MRelatedProduct related : MRelatedProduct.getRelatedLines(product))
				{
					// The conditional UOM works to check if the parent product has that UOM the related product is created. If it's blank it's always created.
					int conditionalUOM = related.get_ValueAsInt("C_UOM_ID");

					if( conditionalUOM == 0 || conditionalUOM == invoiceLine.getC_UOM_ID() ){

						MInvoiceLine newLine = new MInvoiceLine(invoice);
						newLine.setLine(++lineNo);
						newLine.setM_Product_ID(related.getRelatedProduct_ID(), true);
						newLine.setQty(invoiceLine.getQtyEntered());
						if (related.getDescription() != null)
							newLine.setDescription(related.getDescription());
						newLine.setPrice();
						newLine.set_ValueOfColumn("Bay_MasterInvoiceLine_ID", invoiceLine.get_ID());
						newLine.saveEx(invoice.get_TrxName());
					}
				}
			}  catch (Exception e) {
			     throw new AdempiereException("Error creating invoice line. Cause: " + e.getLocalizedMessage());
		    }

		}

	} //createSupplementalInvoiceLines
	
	public boolean hasRelatedProducts(MProduct product){
		if(	MRelatedProduct.getRelatedLines(product) == null || MRelatedProduct.getRelatedLines(product).size()==0 )
			return false;
		return true;
	}

}
