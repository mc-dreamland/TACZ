package com.tacz.guns.paper.command;

import com.tacz.guns.paper.inventory.PaperSupplyMenu;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaczCommandTest {
    @Test void permissionGrantCannotExposeOrOpenOperatorSupplyMenu() {
        PaperSupplyMenu supplies = mock(PaperSupplyMenu.class);
        TaczCommand commands = new TaczCommand(null, null, null, null, supplies, null, null);
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);

        assertTrue(commands.onCommand(player, null, "tacz", new String[]{"supplies"}));
        verifyNoInteractions(supplies);
        verify(player).sendMessage(contains("仅限 OP"));
        assertFalse(commands.onTabComplete(player, null, "tacz", new String[]{"s"}).contains("supplies"));
    }

    @Test void operatorPlayerCanOpenAndDiscoverSupplyMenu() {
        PaperSupplyMenu supplies = mock(PaperSupplyMenu.class);
        TaczCommand commands = new TaczCommand(null, null, null, null, supplies, null, null);
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);

        assertTrue(commands.onCommand(player, null, "tacz", new String[]{"supplies"}));
        verify(supplies).open(player);
        assertTrue(commands.onTabComplete(player, null, "tacz", new String[]{"sup"}).contains("supplies"));

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.isOp()).thenReturn(true);
        when(console.hasPermission(anyString())).thenReturn(true);
        assertFalse(commands.onTabComplete(console, null, "tacz", new String[]{"sup"}).contains("supplies"));
    }
}
