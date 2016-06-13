package fr.galaxyoyo.mobdefense.towers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class SimpleTower extends Tower
{
	public SimpleTower(Location location)
	{
		super(location);
	}

	public static String getName()
	{
		return "Simple Tower";
	}

	public static ItemStack[] getPrice()
	{
		return new ItemStack[]{new ItemStack(Material.GOLD_NUGGET, 5)};
	}

	@Override
	public Material getMaterial()
	{
		return Material.WOOD;
	}

	@Override
	public void onTick()
	{
		launchArrow(10);
	}
}