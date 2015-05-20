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

package de.bxservice.model;

import java.math.BigDecimal;
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
import org.compiere.util.Env;
import org.compiere.util.Msg;
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
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MInvoiceLine.Table_Name);

		//Sales Order
		registerTableEvent(IEventTopics.PO_AFTER_NEW, MOrderLine.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, MOrderLine.Table_Name);
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MOrderLine.Table_Name);

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
		if ( (po instanceof MOrderLine || po instanceof MInvoiceLine) 
				&& type.equals(IEventTopics.PO_BEFORE_DELETE)  ) {

			nonDeletingSupplementalLines(po);

		}
	} //doHandleEvent

	/**
	 * Don't let the supplementary lines be deleted.
	 * Only when the parent is deleted.
	 * @param po
	 */
	private void nonDeletingSupplementalLines(PO po) {
		if( (po instanceof MOrderLine && po.get_Value("Bay_MasterOrderLine_ID") != null ) 
				|| (po instanceof MInvoiceLine && po.get_Value("Bay_MasterInvoiceLine_ID") != null ) )
			throw new AdempiereException(Msg.getMsg(Env.getLanguage(Env.getCtx()), "BAY_SupplementalProducts"));

	}//nonDeletingSupplementalLines

	public void createSupplementalOrderLines(MOrderLine orderLine, String type){

		MOrder order = orderLine.getParent();
		MProduct product = orderLine.getProduct();
		
		//Supplemental lines can't be modified
		if (type.equals(IEventTopics.PO_AFTER_CHANGE) && 
				orderLine.get_Value("Bay_MasterOrderLine_ID") != null){
			
			throw new AdempiereException(Msg.getMsg(Env.getLanguage(Env.getCtx()), "BAY_SupplementalProducts"));
		}

		if ( product != null && hasRelatedProducts(product) ){
			try {
				log.info("Creating related products for: "+product.getName() + " in order: " + order.get_ID());

				int lineNo = orderLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)){
					
					for( MOrderLine line : order.getLines() ){
						if( line.get_Value("Bay_MasterOrderLine_ID")!=null && 
								line.get_Value("Bay_MasterOrderLine_ID").equals(orderLine.get_ID()) ){
							//If the change is made when the document is completed don't do anything
							if( line.getQtyEntered().equals(orderLine.getQtyEntered()) )
								return;
							line.set_ValueOfColumn("Bay_MasterOrderLine_ID", null);   //Allows delete when master is deleted
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
						if(related.get_ValueAsInt("Qty")!=0)
							newLine.setQty(BigDecimal.valueOf(related.get_ValueAsInt("Qty")).multiply(orderLine.getQtyEntered()));
						else
							newLine.setQty(BigDecimal.valueOf(1));
						if (related.getDescription() != null)
							newLine.setDescription(related.getDescription());
						newLine.setPrice();
						newLine.set_ValueOfColumn("Bay_MasterOrderLine_ID", orderLine.get_ID());
						newLine.saveEx(order.get_TrxName());

						log.info("A new sales order line was added with product: "+related.getRelatedProduct().getName());
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
		
		//Supplemental lines can't be modified
		if (type.equals(IEventTopics.PO_AFTER_CHANGE) && 
				invoiceLine.get_Value("Bay_MasterInvoiceLine_ID") != null){
			
			throw new AdempiereException(Msg.getMsg(Env.getLanguage(Env.getCtx()), "BAY_SupplementalProducts"));
		}

		if ( product != null && hasRelatedProducts(product) && invoiceLine.getM_InOutLine_ID() == 0 ){
			try {
				log.info("Creating related products for: "+product.getName() + " in invoice: " + invoice.get_ID());

				int lineNo = invoiceLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)){
					for(MInvoiceLine line :invoice.getLines()){
						if( line.get_Value("Bay_MasterInvoiceLine_ID")!=null && 
								line.get_Value("Bay_MasterInvoiceLine_ID").equals(invoiceLine.get_ID()) ){
							//If the change is made when the document is completed don't do anything
							if( line.getQtyEntered().equals(invoiceLine.getQtyEntered()) )
								return;
							line.set_ValueOfColumn("Bay_MasterInvoiceLine_ID", null); //Allows delete when master is deleted
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
						if(related.get_ValueAsInt("Qty")!=0)
							newLine.setQty(BigDecimal.valueOf(related.get_ValueAsInt("Qty")).multiply(invoiceLine.getQtyEntered()));
						else
							newLine.setQty(BigDecimal.valueOf(1));						
						if (related.getDescription() != null)
							newLine.setDescription(related.getDescription());
						newLine.setPrice();
						newLine.set_ValueOfColumn("Bay_MasterInvoiceLine_ID", invoiceLine.get_ID());
						newLine.saveEx(invoice.get_TrxName());

						log.info("A new invoice line was added with product: "+related.getRelatedProduct().getName());
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
	}//hasRelatedProducts

}
