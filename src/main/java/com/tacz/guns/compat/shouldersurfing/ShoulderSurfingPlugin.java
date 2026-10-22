package com.tacz.guns.compat.shouldersurfing;

import com.tacz.guns.api.item.ItemBehavior;

import com.github.exopandora.shouldersurfing.api.plugin.IShoulderSurfingPlugin;
import com.github.exopandora.shouldersurfing.api.event.IEventBus;
import com.github.exopandora.shouldersurfing.api.client.event.handler.ComputePlayerAimStateEventHandler;
import com.tacz.guns.api.item.IGun;

public class ShoulderSurfingPlugin implements IShoulderSurfingPlugin {
	@Override
	public void register(IEventBus eventBus) {
		eventBus.register((ComputePlayerAimStateEventHandler) event -> {
			if (ItemBehavior.of(event.getEntity().getMainHandItem()) instanceof IGun
					|| ItemBehavior.of(event.getEntity().getOffhandItem()) instanceof IGun) {
				event.setResult(true);
			}
		});
	}
}
