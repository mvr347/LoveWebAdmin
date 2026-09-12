package me.lovelace.loveWebAdmin.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Holder для меню жалоб, позволяющий однозначно идентифицировать инвентарь без сравнения заголовков.
 */
public class ReportInventoryHolder implements InventoryHolder {

    private final ReportGuiSession session;
    private Inventory inventory;

    public ReportInventoryHolder(ReportGuiSession session) {
        this.session = session;
    }

    public ReportGuiSession getSession() {
        return session;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
