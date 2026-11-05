#include "vk_api.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace film::vk {

#define FILM_VK_DEFINE(name) PFN_##name name = nullptr;
FILM_VK_GLOBAL(FILM_VK_DEFINE)
FILM_VK_INSTANCE(FILM_VK_DEFINE)
FILM_VK_DEVICE(FILM_VK_DEFINE)
#undef FILM_VK_DEFINE

static PFN_vkGetInstanceProcAddr get_instance_proc = nullptr;

bool load_loader(const char **error)
{
  if(get_instance_proc) return true;
#if defined(_WIN32)
  HMODULE library = LoadLibraryA("vulkan-1.dll");
  if(library) get_instance_proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
      reinterpret_cast<void *>(GetProcAddress(library, "vkGetInstanceProcAddr")));
#else
  void *library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
  if(!library) library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
  if(library) get_instance_proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
#endif
  if(!get_instance_proc)
  {
    *error = "Vulkan loader not found";
    return false;
  }
  vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(get_instance_proc(nullptr, "vkCreateInstance"));
  // Absent on Vulkan 1.0 loaders; callers treat null as 1.0.
  vkEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
      get_instance_proc(nullptr, "vkEnumerateInstanceVersion"));
  if(!vkCreateInstance)
  {
    *error = "vkCreateInstance missing";
    return false;
  }
  return true;
}

bool load_instance(VkInstance instance, const char **error)
{
#define FILM_VK_LOAD(name) \
  name = reinterpret_cast<PFN_##name>(get_instance_proc(instance, #name)); \
  if(!name) { *error = #name " missing"; return false; }
  FILM_VK_INSTANCE(FILM_VK_LOAD)
#undef FILM_VK_LOAD
  return true;
}

bool load_device(VkDevice device, const char **error)
{
#define FILM_VK_LOAD(name) \
  name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name)); \
  if(!name) { *error = #name " missing"; return false; }
  FILM_VK_DEVICE(FILM_VK_LOAD)
#undef FILM_VK_LOAD
  return true;
}

} // namespace film::vk
