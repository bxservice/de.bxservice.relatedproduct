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

import java.util.logging.Level;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
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

			MInvoiceLine invoiceLine = (MInvoiceLine)po;
			MInvoice invoice = (MInvoice) invoiceLine.getC_Invoice();
			MProduct product = MProduct.get (Env.getCtx(), invoiceLine.getM_Product_ID());
			int lineNo = invoiceLine.getLine();

			if ( invoice.isSOTrx() ){
				try {
					//If the record was modified delete previous supplementary lines to avoid repeated values
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
						if(related.get_Value("C_UOM_ID") == null || 
								related.get_Value("C_UOM_ID").equals("") || 
								related.get_Value("C_UOM_ID").equals(invoiceLine.getC_UOM_ID())){

							MInvoiceLine newLine = new MInvoiceLine(invoice);
							newLine.setLine(++lineNo);
							newLine.setM_Product_ID(related.getRelatedProduct_ID(), true);
							newLine.setQty(invoiceLine.getQtyInvoiced());
							if (related.getDescription() != null)
								newLine.setDescription(related.getDescription());
							newLine.setPrice();
							newLine.set_ValueOfColumn("Bay_MasterInvoiceLine_ID", invoiceLine.get_ID());
							newLine.save(invoice.get_TrxName());
						}
					}
				} catch (Exception e) {
					log.log(Level.SEVERE, "Error creating invoice line for invoice "+invoice.get_ID(), e);
				}

			}
		}
		if (po instanceof MOrderLine && 
				(type.equals(IEventTopics.PO_AFTER_NEW) || 
						type.equals(IEventTopics.PO_AFTER_CHANGE) ) ) {

			MOrderLine orderLine = (MOrderLine)po;
			MOrder order = (MOrder) orderLine.getC_Order();
			MProduct product = MProduct.get (Env.getCtx(), orderLine.getM_Product_ID());
			int lineNo = orderLine.getLine();

			if ( order.isSOTrx() ){
				try {
					//If the record was modified delete previous supplementary lines to avoid repeated values
					if (type.equals(IEventTopics.PO_AFTER_CHANGE)){
						for(MOrderLine line :order.getLines()){
							if( line.get_Value("Bay_MasterOrderLine_ID")!=null && 
									line.get_Value("Bay_MasterOrderLine_ID").equals(orderLine.get_ID()) ){
								line.deleteEx(true, order.get_TrxName());
							}
						}
					}
					
					for (MRelatedProduct related : MRelatedProduct.getRelatedLines(product))
					{
						if(related.get_Value("C_UOM_ID") == null || 
								related.get_Value("C_UOM_ID").equals("") || 
								related.get_Value("C_UOM_ID").equals(orderLine.getC_UOM_ID())){
							
							MOrderLine newLine = new MOrderLine(order);
							newLine.setLine(++lineNo);
							newLine.setM_Product_ID(related.getRelatedProduct_ID(), true);
							newLine.setQty(orderLine.getQtyOrdered());
							if (related.getDescription() != null)
								newLine.setDescription(related.getDescription());
							newLine.setPrice();
							newLine.set_ValueOfColumn("Bay_MasterOrderLine_ID", orderLine.get_ID());
							newLine.save(order.get_TrxName());
						}
					}
				} catch (Exception e) {
					log.log(Level.SEVERE, "Error creating order line for order "+order.get_ID(), e);
				}
			}
		}
	} //doHandleEvent

}
