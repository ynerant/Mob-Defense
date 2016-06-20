package fr.galaxyoyo.mobdefense;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.galaxyoyo.mobdefense.events.GameStartedEvent;
import fr.galaxyoyo.mobdefense.events.GameStoppedEvent;
import fr.galaxyoyo.mobdefense.towers.Tower;
import fr.galaxyoyo.mobdefense.towers.TowerRegistration;
import fr.galaxyoyo.spigot.nbtapi.ItemStackUtils;
import net.md_5.bungee.api.ChatColor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_10_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_10_R1.entity.CraftVillager;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.mcstats.Metrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class MobDefense extends JavaPlugin
{
	private static MobDefense instance;
	private String latestVersion;
	private Gson gson;
	private Map<String, MobClass> mobClasses = Maps.newHashMap();
	private Location playerSpawn;
	private Location spawn, end;
	private int startMoney;
	private int waveTime;
	private int baseLives;
	private Location npcTowerLoc;
	private Location npcUpgradesLoc;
	private Location npcExchangeLoc;
	private List<Wave> waves = Lists.newArrayList();
	private Wave currentWave;
	private Objective objective;

	@Override
	public void onLoad()
	{
		JavaPlugin nbtapi = (JavaPlugin) getServer().getPluginManager().getPlugin("NBTAPI");
		String latestNBTAPIVersion = getLatestSpigotVersion(24908);
		boolean needToUpdate = nbtapi == null;
		if (nbtapi != null && new Version(nbtapi.getDescription().getVersion()).compareTo(new Version(latestNBTAPIVersion)) < 0)
		{
			needToUpdate = true;
			getServer().getPluginManager().disablePlugin(nbtapi);
		}
		if (needToUpdate)
		{
			getLogger().info("Downloading version " + latestNBTAPIVersion + " of NBTAPI ...");
			try
			{
				File file = new File("plugins", "NBTAPI.jar");
				URL url = getLatestDownloadURL("nbtapi", 24908);
				HttpURLConnection co = (HttpURLConnection) url.openConnection();
				co.setRequestMethod("GET");
				co.setRequestProperty("User-Agent", "Mozilla/5.0");
				co.setRequestProperty("Connection", "Close");
				co.connect();
				FileUtils.copyInputStreamToFile(co.getInputStream(), file);
				co.disconnect();
				getServer().getPluginManager().loadPlugin(file);
			}
			catch (IOException e)
			{
				getLogger().severe("Unable to download NBTAPI library. Make sure you have the latest version of MobDefense and have an Internet connection.");
				getLogger().severe("Plugin will disable now.");
				e.printStackTrace();
				getServer().getPluginManager().disablePlugin(this);
			}
			catch (InvalidPluginException | InvalidDescriptionException e)
			{
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onDisable()
	{
		stop(null);
	}

	@Override
	public void onEnable()
	{
		instance = this;

		getServer().getPluginManager().registerEvents(new MobDefenseListener(), this);

		MobDefenseExecutor executor = new MobDefenseExecutor();
		getCommand("mobdefense").setExecutor(executor);
		getCommand("mobdefense").setTabCompleter(executor);

		gson = new GsonBuilder().registerTypeAdapter(ItemStack.class, new ItemStackTypeAdapter()).registerTypeAdapter(Wave.class, new WaveTypeAdapter()).setPrettyPrinting().create();

		try
		{
			String version = getLatestSpigotVersion(Integer.parseInt(IOUtils.toString(new URL("http://arathia.fr/mobdefense-resourceid.txt"))));
			if (new Version(getDescription().getVersion()).compareTo(new Version(version)) < 0)
			{
				getLogger().warning(
						"This plugin is outdated. The last version is " + version + " and you're running " + getDescription().getVersion() + ". Please update, there're maybe some " +
								"fixes or new features.");
				latestVersion = version;
			}

			if (!getDataFolder().isDirectory())
				//noinspection ResultOfMethodCallIgnored
				getDataFolder().mkdir();
			File configFile = new File(getDataFolder(), "config.yml");
			if (!configFile.exists())
				IOUtils.copy(getClass().getResourceAsStream("/config.yml"), FileUtils.openOutputStream(configFile));

			World world = Bukkit.getWorlds().get(0);
			YamlConfiguration config = (YamlConfiguration) getConfig();
			String playerSpawnStr = config.getString("player-spawn-loc", LocationConverter.instance().toString(world.getSpawnLocation()));
			playerSpawn = LocationConverter.instance().fromString(playerSpawnStr);
			String spawnStr = config.getString("spawn-loc", LocationConverter.instance().toString(world.getSpawnLocation()));
			spawn = LocationConverter.instance().fromString(spawnStr);
			String endStr = config.getString("end-loc", LocationConverter.instance().toString(world.getSpawnLocation()));
			end = LocationConverter.instance().fromString(endStr);
			String towerLoc = config.getString("npc-tower-loc", LocationConverter.instance().toString(world.getSpawnLocation()));
			npcTowerLoc = LocationConverter.instance().fromString(towerLoc);
			String upgradesLoc = config.getString("npc-upgrades-loc", LocationConverter.instance().toString(world.getSpawnLocation()));
			npcUpgradesLoc = LocationConverter.instance().fromString(upgradesLoc);
			String exchangeLoc = config.getString("npc-exchange-loc", LocationConverter.instance().toString(world.getSpawnLocation()));
			npcExchangeLoc = LocationConverter.instance().fromString(exchangeLoc);
			startMoney = config.getInt("start-money", 50);
			waveTime = config.getInt("wave-time", 42);
			baseLives = config.getInt("lives", 10);

			File file = new File(getDataFolder(), "mobs.json");
			if (file.exists())
				//noinspection unchecked
				((List<MobClass>) getGson().fromJson(FileUtils.readFileToString(file, StandardCharsets.UTF_8), new TypeToken<ArrayList<MobClass>>() {}.getType())).stream()
						.forEach(mobClass -> mobClasses.put(mobClass.getName(), mobClass));
			else
			{
				//noinspection ResultOfMethodCallIgnored
				file.createNewFile();

				ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
				helmet.addUnsafeEnchantment(Enchantment.DURABILITY, 42);
				ItemStack chestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);
				chestplate.addUnsafeEnchantment(Enchantment.THORNS, 42);
				ItemStack leggings = new ItemStack(Material.DIAMOND_LEGGINGS);
				leggings.addUnsafeEnchantment(Enchantment.PROTECTION_EXPLOSIONS, 42);
				ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
				boots.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, 42);
				ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
				sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 42);
				ItemStack shield = new ItemStack(Material.SHIELD);
				shield.addUnsafeEnchantment(Enchantment.ARROW_KNOCKBACK, 42);
				ItemMeta meta = shield.getItemMeta();
				meta.spigot().setUnbreakable(true);
				shield.setItemMeta(meta);
				MobClass sample = new MobClass("sample", "Sample Zombie", 42, 1.0F, EntityType.ZOMBIE, new ItemStack[]{helmet, chestplate, leggings, boots, sword, shield}, 42);
				mobClasses.put(sample.getName(), sample);
			}
			FileUtils.writeStringToFile(file, getGson().toJson(mobClasses.values()), StandardCharsets.UTF_8);

			file = new File(getDataFolder(), "waves.json");
			if (file.exists())
				waves.addAll(getGson().fromJson(FileUtils.readFileToString(file, StandardCharsets.UTF_8), new TypeToken<ArrayList<Wave>>() {}.getType()));
			else
			{
				Wave wave1 = new Wave();
				wave1.setNumber(1);
				//noinspection OptionalGetWithoutIsPresent
				wave1.getSpawns().put(mobClasses.values().stream().findAny().get(), 5);
				waves.add(wave1);

				Wave wave2 = new Wave();
				wave2.setNumber(2);
				//noinspection OptionalGetWithoutIsPresent
				wave2.getSpawns().put(mobClasses.values().stream().findAny().get(), 10);
				waves.add(wave2);
			}
			FileUtils.writeStringToFile(file, getGson().toJson(waves), StandardCharsets.UTF_8);

			file = new File(getDataFolder(), "towers.json");
			if (file.exists())
				//noinspection unchecked
				((ArrayList<TowerRegistration>) getGson().fromJson(FileUtils.readFileToString(file, StandardCharsets.UTF_8), new TypeToken<ArrayList<TowerRegistration>>() {}.getType
						())).forEach(Tower::registerTower);
			else
			{
				TowerRegistration basic = new TowerRegistration("SimpleTower", "Simple Tower", Lists.newArrayList("Launches basic arrows once every 1/2 second.", "It is the most " +
						"basic tower you can find, but not the cheapest :)"), new ItemStack[]{new ItemStack(Material.GOLD_NUGGET, 5)}, Material.WOOD);
				Tower.registerTower(basic);
				TowerRegistration healing = new TowerRegistration("HealingTower", "Healing Tower",
						Lists.newArrayList("Launches Instant Healing arrows.", "Remember: Instant Healing deals damage to zombies, skeletons and pigmens!"),
						new ItemStack[]{new ItemStack(Material.GOLD_INGOT, 1)}, Material.BEACON);
				Tower.registerTower(healing);
				TowerRegistration spectral =
						new TowerRegistration("SpectralTower", "Spectral Tower", Lists.newArrayList("Launches basic spectral arrows.", "It's not very useful, but it looks cool ..."),
								new ItemStack[]{new ItemStack(Material.GOLD_NUGGET, 7)}, Material.GLOWSTONE);
				Tower.registerTower(spectral);
				TowerRegistration damage = new TowerRegistration("DamageTower", "Damage Tower", Lists.newArrayList("Launches Instant Damage arrows.", "Remember: instant damage heals" +
						" " +
						"zombies, skeletons and pigmens!"), new ItemStack[]{new ItemStack(Material.GOLD_NUGGET, 3)}, Material.NETHER_WART_BLOCK);
				Tower.registerTower(damage);
				TowerRegistration poison =
						new TowerRegistration("PoisonTower", "Poison Tower", Lists.newArrayList("Launches Poison arrows.", "Remember: poison heals zombies, skeletons and pigmens!"),
								new ItemStack[]{new ItemStack(Material.GOLD_NUGGET, 3)}, Material.SLIME_BLOCK);
				Tower.registerTower(poison);
			}
			FileUtils.writeStringToFile(file, getGson().toJson(Tower.getTowerRegistrations()), StandardCharsets.UTF_8);

			world.getEntities().stream().filter(entity -> !(entity instanceof Player)).forEach(Entity::remove);

			Metrics metrics = new Metrics(this);
			metrics.start();
		}
		catch (IOException ex)
		{
			ex.printStackTrace();
		}
	}

	public Gson getGson()
	{
		return gson;
	}

	public void stop(CommandSender sender)
	{
		if (objective == null)
		{
			if (sender != null)
				sender.sendMessage("[MobDefense] No game is running!");
			return;
		}

		Bukkit.broadcastMessage("[MobDefense] Game ended.");

		for (Tower tower : Tower.getAllTowers())
			Tower.breakAt(tower.getLocation());
		Bukkit.getWorlds().get(0).getEntities().stream().filter(entity -> entity.getType() != EntityType.PLAYER).forEach(Entity::remove);
		Bukkit.getOnlinePlayers().forEach(player -> player.getInventory().clear());
		getServer().getPluginManager().callEvent(new GameStoppedEvent(currentWave == null ? 0 : currentWave.getNumber()));
		currentWave = null;
		objective.unregister();
		objective = null;
		Bukkit.getScheduler().cancelTasks(MobDefense.instance());
	}

	public static MobDefense instance()
	{
		return instance;
	}

	private String getLatestSpigotVersion(int resourceId)
	{
		try
		{
			HttpURLConnection con = (HttpURLConnection) new URL("http://www.spigotmc.org/api/general.php").openConnection();
			con.setDoOutput(true);
			con.setRequestMethod("POST");
			con.getOutputStream().write(
					("key=98BE0FE67F88AB82B4C197FAF1DC3B69206EFDCC4D3B80FC83A00037510B99B4&resource=" + resourceId).getBytes("UTF-8"));
			String version = new BufferedReader(new InputStreamReader(con.getInputStream())).readLine();
			if (version.length() <= 7)
				return version;
		}
		catch (Exception ex)
		{
			getLogger().warning("Failed to check for a update on spigot.");
		}
		return null;
	}

	public URL getLatestDownloadURL(String resourceName, int resourceId)
	{
		try
		{
			String url = "https://www.spigotmc.org/resources/" + (resourceName != null ? resourceName + "." : "") + resourceId + "/";
			URL u = new URL(url);
			HttpURLConnection co = (HttpURLConnection) u.openConnection();
			co.setRequestMethod("GET");
			co.setRequestProperty("User-Agent", "Mozilla/5.0");
			co.setRequestProperty("Connection", "Keep-Alive");
			co.connect();
			String content = IOUtils.toString(co.getInputStream(), StandardCharsets.UTF_8);
			co.disconnect();
			String innerLabel = content.substring(content.indexOf("<label class=\"downloadButton \">"));
			innerLabel = innerLabel.substring(0, innerLabel.indexOf("</label>"));
			String downloadURL = innerLabel.substring(innerLabel.indexOf("<a href=\"") + 9);
			downloadURL = downloadURL.substring(0, downloadURL.indexOf("\" class=\"inner\">"));
			downloadURL = "https://www.spigotmc.org/" + downloadURL;
			return new URL(downloadURL);
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}

		return null;
	}

	public String getLatestVersion()
	{
		return latestVersion;
	}

	public Location getPlayerSpawn()
	{
		return playerSpawn;
	}

	protected void setPlayerSpawn(Location location)
	{
		this.playerSpawn = location;
		getConfig().set("player-spawn-loc", LocationConverter.instance().toString(location));
	}

	public Location getSpawn()
	{
		return spawn;
	}

	protected void setSpawn(Location location)
	{
		this.spawn = location;
		getConfig().set("spawn-loc", LocationConverter.instance().toString(location));
	}

	public Location getEnd()
	{
		return end;
	}

	protected void setEnd(Location location)
	{
		this.end = location;
		getConfig().set("end-loc", LocationConverter.instance().toString(location));
	}

	@SuppressWarnings("unused")
	public int getBaseLives()
	{
		return baseLives;
	}

	public MobClass getMobClass(String name)
	{
		return mobClasses.get(name);
	}

	public void startNextWave()
	{
		if (currentWave == null)
			currentWave = waves.get(0);
		else
		{
			int index = waves.indexOf(currentWave);
			if (index != waves.size() - 1)
				currentWave = waves.get(index + 1);
			else
			{
				currentWave.setNumber(currentWave.getNumber() + 1);
				currentWave.getSpawns().entrySet().forEach(entry -> entry.setValue((int) (entry.getValue() * 1.2D)));
			}
		}

		objective.getScore("Wave").setScore(currentWave.getNumber());
		currentWave.start();
	}

	public void start(CommandSender sender)
	{
		if (objective != null)
		{
			sender.sendMessage(ChatColor.RED + "A game is already started!");
			return;
		}

		Player giveTo;
		if (sender instanceof Player)
			giveTo = (Player) sender;
		else
		{
			List<Player> players = Lists.newArrayList(Bukkit.getOnlinePlayers());
			if (players.isEmpty())
			{
				sender.sendMessage(ChatColor.RED + "Any player is connected to start the game!");
				return;
			}
			else
				giveTo = players.get(((CraftWorld) Bukkit.getWorlds().get(0)).getHandle().random.nextInt(players.size()));
		}

		int remainingMoney = startMoney;
		while (remainingMoney > 0)
		{
			giveTo.getInventory().addItem(new ItemStack(Material.GOLD_NUGGET, Math.min(remainingMoney, 64)));
			remainingMoney -= 64;
		}

		World world = Bukkit.getWorlds().get(0);
		Random random = ((CraftWorld) world).getHandle().random;

		for (int i = 0; i < 3; ++i)
		{
			Location loc = npcTowerLoc.clone().add(random.nextDouble() * 3.0D - 1.5D, 0, random.nextDouble() * 3.0D - 1.5D);
			Villager npcTower = (Villager) world.spawnEntity(loc, EntityType.VILLAGER);
			npcTower.setCollidable(false);
			npcTower.setAI(false);
			((CraftVillager) npcTower).getHandle().h(loc.getYaw());
			((CraftVillager) npcTower).getHandle().i(loc.getYaw());
			npcTower.setProfession(Villager.Profession.FARMER);
			List<MerchantRecipe> recipes = Lists.newArrayList();
			for (TowerRegistration tr : Tower.getTowerRegistrations())
			{
				ItemStack result = new ItemStack(Material.DISPENSER);
				ItemMeta meta = result.getItemMeta();
				meta.setDisplayName(tr.getDisplayName());
				meta.setLore(tr.getLore());
				meta.addItemFlags(ItemFlag.HIDE_PLACED_ON);
				result.setItemMeta(meta);
				List<Material> list = Arrays.stream(Material.values()).filter(Material::isSolid).collect(Collectors.toList());
				ItemStackUtils.setCanPlaceOn(result, list.toArray(new Material[list.size()]));
				MerchantRecipe recipe = new MerchantRecipe(result, 0, Integer.MAX_VALUE, false);
				recipe.setIngredients(Lists.newArrayList(tr.getCost()));
				recipes.add(recipe);
			}
			npcTower.setRecipes(recipes);
			npcTower.setCustomName("Towers");
		}

		for (int i = 0; i < 3; ++i)
		{
			Location loc = npcUpgradesLoc.clone().add(random.nextDouble() * 3.0D - 1.5D, 0, random.nextDouble() * 3.0D - 1.5D);
			Villager npcUpgrades = (Villager) world.spawnEntity(loc, EntityType.VILLAGER);
			npcUpgrades.setCollidable(false);
			npcUpgrades.setAI(false);
			((CraftVillager) npcUpgrades).getHandle().h(loc.getYaw());
			((CraftVillager) npcUpgrades).getHandle().i(loc.getYaw());
			npcUpgrades.setProfession(Villager.Profession.LIBRARIAN);
			npcUpgrades.setRecipes(Lists.newArrayList());
			npcUpgrades.setCustomName("Upgrades (Soon ...)");
		}

		for (int i = 0; i < 3; ++i)
		{
			Location loc = npcExchangeLoc.clone().add(random.nextDouble() * 4.0D - 1.0D, 0, random.nextDouble() * 3.0D - 1.5D);
			Villager npcExchange = (Villager) world.spawnEntity(loc, EntityType.VILLAGER);
			npcExchange.setCollidable(false);
			npcExchange.setAI(false);
			((CraftVillager) npcExchange).getHandle().h(loc.getYaw());
			((CraftVillager) npcExchange).getHandle().i(loc.getYaw());
			npcExchange.setProfession(Villager.Profession.BLACKSMITH);
			MerchantRecipe nuggetToIngot = new MerchantRecipe(new ItemStack(Material.GOLD_INGOT), 0, Integer.MAX_VALUE, false);
			MerchantRecipe ingotToNugget = new MerchantRecipe(new ItemStack(Material.GOLD_NUGGET, 9), 0, Integer.MAX_VALUE, false);
			MerchantRecipe ingotToBlock = new MerchantRecipe(new ItemStack(Material.GOLD_BLOCK), 0, Integer.MAX_VALUE, false);
			MerchantRecipe blockToIngot = new MerchantRecipe(new ItemStack(Material.GOLD_INGOT, 9), 0, Integer.MAX_VALUE, false);
			MerchantRecipe blockToEmerald = new MerchantRecipe(new ItemStack(Material.EMERALD), 0, Integer.MAX_VALUE, false);
			MerchantRecipe emeraldToBlock = new MerchantRecipe(new ItemStack(Material.GOLD_BLOCK, 9), 0, Integer.MAX_VALUE, false);
			MerchantRecipe emeraldToEBlock = new MerchantRecipe(new ItemStack(Material.EMERALD_BLOCK), 0, Integer.MAX_VALUE, false);
			MerchantRecipe eBlockToEmerald = new MerchantRecipe(new ItemStack(Material.EMERALD, 9), 0, Integer.MAX_VALUE, false);
			nuggetToIngot.addIngredient(new ItemStack(Material.GOLD_NUGGET, 9));
			ingotToNugget.addIngredient(new ItemStack(Material.GOLD_INGOT));
			ingotToBlock.addIngredient(new ItemStack(Material.GOLD_INGOT, 9));
			blockToIngot.addIngredient(new ItemStack(Material.GOLD_BLOCK));
			blockToEmerald.addIngredient(new ItemStack(Material.GOLD_BLOCK, 9));
			emeraldToBlock.addIngredient(new ItemStack(Material.EMERALD));
			emeraldToEBlock.addIngredient(new ItemStack(Material.EMERALD, 9));
			eBlockToEmerald.addIngredient(new ItemStack(Material.EMERALD_BLOCK));
			npcExchange
					.setRecipes(Lists.newArrayList(nuggetToIngot, ingotToNugget, ingotToBlock, blockToIngot, blockToEmerald, emeraldToBlock, emeraldToEBlock, eBlockToEmerald));
			npcExchange.setCustomName("Exchange");
		}

		objective = Bukkit.getScoreboardManager().getMainScoreboard().registerNewObjective("mobdefense", "dummy");
		objective.setDisplayName("[MobDefense]");
		objective.setDisplaySlot(DisplaySlot.SIDEBAR);
		objective.getScore(ChatColor.RED.toString()).setScore(999);
		objective.getScore("Lives").setScore(baseLives);
		objective.getScore("Wave").setScore(0);

		getServer().getPluginManager().callEvent(new GameStartedEvent());
		Bukkit.broadcastMessage("[MobDefense] Game started!");
		Bukkit.getScheduler().runTaskLater(this, this::startNextWave, waveTime * 20L);
	}

	public Wave getCurrentWave()
	{
		return currentWave;
	}

	public int getWaveTime()
	{
		return waveTime;
	}

	protected void setNpcTowerLoc(Location location)
	{
		this.npcTowerLoc = location;
		getConfig().set("npc-tower-loc", LocationConverter.instance().toString(location));
	}

	protected void setNpcUpgradesLoc(Location location)
	{
		this.npcUpgradesLoc = location;
		getConfig().set("npc-upgrades-loc", LocationConverter.instance().toString(location));
	}

	protected void setNpcExchangeLoc(Location location)
	{
		this.npcExchangeLoc = location;
		getConfig().set("npc-exchange-loc", LocationConverter.instance().toString(location));
	}
}
