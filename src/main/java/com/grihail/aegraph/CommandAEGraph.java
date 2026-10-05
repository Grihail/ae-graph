package com.grihail.aegraph;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import com.grihail.aegraph.ae.GraphCraftingJob;

public class CommandAEGraph extends CommandBase {

    @Override
    public String getCommandName() {
        return "aegraph";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/aegraph <status|on|off|reload|log>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1) throw new WrongUsageException(getCommandUsage(sender));
        switch (args[0]) {
            case "status":
                reply(sender, "Graph crafting: " + (AEGraphConfig.enabled ? "ON" : "OFF") + ", plans logged: "
                        + AEGraphConfig.logPlans);
                final String last = GraphCraftingJob.lastPlanSummary;
                if (last != null) reply(sender, "Last plan: " + last);
                break;
            case "on":
                AEGraphConfig.enabled = true;
                reply(sender, "Graph crafting enabled (until restart; edit config/aegraph.cfg to persist).");
                break;
            case "off":
                AEGraphConfig.enabled = false;
                reply(sender, "Graph crafting disabled, AE2 uses its own calculator (until restart).");
                break;
            case "log":
                AEGraphConfig.logPlans = !AEGraphConfig.logPlans;
                reply(sender, "Plan logging: " + AEGraphConfig.logPlans);
                break;
            case "reload":
                AEGraphConfig.reload();
                reply(sender, "Config reloaded.");
                break;
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "status", "on", "off", "reload", "log");
        }
        return null;
    }

    private static void reply(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText("[AEGraph] " + text));
    }
}
